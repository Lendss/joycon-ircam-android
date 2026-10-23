// SPDX-License-Identifier: MIT
package local.ircam;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * One camera session: runs the root helper (libircam.so, built from ircam.c) as
 * "ircam -o -", copies its 300-byte fragments into {@link #live} and reports to
 * the listener on the main thread. Live settings go to the helper's stdin.
 */
class IrCamera {
    interface Listener {
        void onReady(IrCamera c);

        void onFragment(IrCamera c, int frag, boolean frameDone);

        void onButtons(IrCamera c, int right, int shared);

        /** About once a second: share of image fragments lost on the Bluetooth link (0..1). */
        void onLink(IrCamera c, float loss, int maxGapMs);

        void onStopped(IrCamera c, int code, String error);

        /** A photo from {@link #takePhoto()}: one whole pass; lost fragments taken from the next pass. */
        void onPhoto(IrCamera c, byte[] img, int refilled, int concealed);
    }

    final int res, w, h, frags;
    final byte[] live; // the pass being received; fragments overwrite it in place (lock: itself)
    final byte[] done; // the last complete pass, top to bottom (lock: live)
    volatile boolean ready, alive = true, stopping;
    // From the latest fragment header.
    volatile int avg, exposure, gain, motion;

    private final String cmd;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Process proc;
    private OutputStream stdin;
    private Thread errThread;
    private volatile String lastError = "";
    private int lastFrag = -1; // reader thread

    // Photo (lock: live): ARMED waits for a pass to start, RECORD keeps that whole pass, then
    // REFILL takes the fragments it lost from the next pass, at their own places.
    private static final int PHOTO_OFF = 0, PHOTO_ARMED = 1, PHOTO_RECORD = 2, PHOTO_REFILL = 3;
    private static final int REFILL_MIN_RUN = 3; // shorter gaps are interpolated
    private int photo = PHOTO_OFF;
    private byte[] photoImg, refillImg;
    private boolean[] photoGot, refillGot;

    IrCamera(String helper, int res, int exposure, int gain, int leds, Listener l) {
        this.res = res;
        w = res;
        h = res * 3 / 4;
        frags = w * h / 300;
        live = new byte[w * h];
        done = new byte[w * h];
        this.exposure = exposure;
        this.gain = gain;
        listener = l;
        cmd = "exec " + helper + " -o - -n 0 -r " + res + " -e " + exposure + " -g " + gain + " -l " + leds;
    }

    /** Starts the helper; call on the control thread. */
    void start() {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).start();
            synchronized (this) {
                proc = p;
                stdin = p.getOutputStream();
            }
            errThread = new Thread(() -> drainErrors(p), "ircam-err");
            errThread.start();
            new Thread(() -> read(p), "ircam-read").start();
        } catch (IOException e) {
            finish(-1, e.getMessage());
        }
    }

    private void read(Process p) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(p.getInputStream(), 4096))) {
            byte[] hd = new byte[16], frag = new byte[300];
            while (true) {
                in.readFully(hd);
                if (hd[0] != 'J') throw new IOException("bad stream");
                if (hd[1] == 'F') {
                    in.readFully(frag);
                    final int f = hd[2] & 255;
                    if (f >= frags) continue;
                    synchronized (live) {
                        if (lastFrag >= 0 && f <= lastFrag) { // the pass ended before f: its last ones were lost
                            if (lastFrag + 1 < frags) conceal(live, (lastFrag + 1) * 300, frags * 300, frags * 300);
                            passEnded();
                            lastFrag = -1;
                        }
                        int from = lastFrag + 1; // from < f: fragments lost
                        if (lastFrag < 0) passStarted();
                        System.arraycopy(frag, 0, live, f * 300, 300);
                        photoFragment(f, frag);
                        if (from < f) conceal(live, from * 300, f * 300, f * 300 + 300);
                        if (f == frags - 1) {
                            passEnded();
                            lastFrag = -1;
                        } else lastFrag = f;
                    }
                    avg = hd[4] & 255;
                    exposure = u16(hd, 10);
                    motion = u16(hd, 12);
                    gain = hd[14] & 255;
                    final boolean end = hd[5] != 0;
                    ui.post(() -> listener.onFragment(this, f, end));
                } else if (hd[1] == 'B') {
                    final int r = hd[2] & 255, s = hd[3] & 255;
                    ui.post(() -> listener.onButtons(this, r, s));
                } else if (hd[1] == 'S') {
                    final int got = u16(hd, 4), lost = u16(hd, 6), gap = u16(hd, 8);
                    final float loss = got + lost > 0 ? lost / (float) (got + lost) : 1;
                    ui.post(() -> listener.onLink(this, loss, gap));
                } else if (hd[1] == 'R') {
                    ready = true;
                    ui.post(() -> listener.onReady(this));
                }
            }
        } catch (IOException ignored) {
            // EOF: the helper exited
        }
        int code = -1;
        try {
            if (p.waitFor(5, TimeUnit.SECONDS)) code = p.exitValue();
            else p.destroy();
            if (errThread != null) errThread.join(500);
        } catch (InterruptedException ignored) {
        }
        finish(code, lastError);
    }

    private void finish(int code, String err) {
        alive = false;
        ui.post(() -> listener.onStopped(this, code, err));
    }

    private void drainErrors(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null)
                if (!line.trim().isEmpty()) lastError = line.trim();
        } catch (IOException ignored) {
        }
    }

    /** Sends a command line to the helper (see ircam.c: e, g, l, i, q). */
    synchronized void send(String line) {
        if (stdin == null) return;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
        }
    }

    /** Asks the helper to restore the Joy-Con and exit; call on the control thread. */
    void stopAndWait() {
        stopping = true;
        Process p;
        synchronized (this) {
            p = proc;
        }
        if (p == null) return;
        send("q");
        synchronized (this) {
            try {
                if (stdin != null) stdin.close();
            } catch (IOException ignored) {
            }
            stdin = null;
        }
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Fills pixels [start, end) of buf (lost fragments, still holding an older pass) by interpolating
     * each column between the nearest rows above and below that hold this pass: everything before
     * start, and [end, fresh). A 320 fragment is about one row, so this hides the line a stale row
     * would leave.
     */
    private void conceal(byte[] buf, int start, int end, int fresh) {
        for (int i = start; i < end; i++) {
            int up = i - w, down = i + w, du = 1, dd = 1;
            while (up >= start) { up -= w; du++; }
            while (down < end) { down += w; dd++; }
            boolean hasUp = up >= 0, hasDown = down < fresh;
            if (hasUp && hasDown)
                buf[i] = (byte) (((buf[up] & 255) * dd + (buf[down] & 255) * du) / (du + dd));
            else if (hasUp) buf[i] = buf[up];
            else if (hasDown) buf[i] = buf[down];
        }
    }

    /** Arms a photo: the next whole pass, see {@link Listener#onPhoto}. Any thread. */
    void takePhoto() {
        synchronized (live) {
            photo = PHOTO_ARMED;
        }
    }

    boolean photoBusy() {
        synchronized (live) {
            return photo != PHOTO_OFF;
        }
    }

    private void passStarted() { // lock held
        if (photo != PHOTO_ARMED) return;
        photo = PHOTO_RECORD;
        photoImg = new byte[live.length];
        refillImg = new byte[live.length];
        photoGot = new boolean[frags];
        refillGot = new boolean[frags];
    }

    private void photoFragment(int f, byte[] frag) { // lock held
        if (photo == PHOTO_RECORD) {
            System.arraycopy(frag, 0, photoImg, f * 300, 300);
            photoGot[f] = true;
        } else if (photo == PHOTO_REFILL) { // the whole pass: also tells whether the scene moved
            System.arraycopy(frag, 0, refillImg, f * 300, 300);
            refillGot[f] = true;
        }
    }

    /**
     * Largest mean |photo pass - refill pass| over the 20-pixel blocks of fragment k: a shift shows at
     * edges, which a mean over the whole fragment would dilute. Noise level when still: ~1-1.5.
     */
    private float passDiff(int k) {
        int worst = 0;
        for (int b = k * 300; b < k * 300 + 300; b += 20) {
            int s = 0;
            for (int i = b; i < b + 20; i++) s += Math.abs((photoImg[i] & 255) - (refillImg[i] & 255));
            worst = Math.max(worst, s);
        }
        return worst / 20f;
    }

    private static int pct(int[] hist, double count) {
        int acc = 0;
        for (int v = 0; v < 256; v++)
            if ((acc += hist[v]) > count) return v;
        return 255;
    }

    private void passEnded() { // lock held
        System.arraycopy(live, 0, done, 0, live.length);
        if (photo == PHOTO_RECORD) {
            for (boolean g : photoGot)
                if (!g) {
                    photo = PHOTO_REFILL;
                    return;
                }
        } else if (photo != PHOTO_REFILL) return;
        // Lost fragments. A short gap (a 320 fragment is about one row) is interpolated from the rows
        // around it, like the viewfinder does: invisible. A long burst would smear, so it is taken from
        // the next pass (each pass is one sensor snapshot), but only where the fragments around it
        // match in both passes: if the Joy-Con moved even slightly, the copy would leave lines.
        int[] hist = new int[256];
        for (byte v : photoImg) hist[v & 255]++;
        int lo = pct(hist, photoImg.length * 0.005), hi = pct(hist, photoImg.length * 0.995);
        float still = 0.01f * Math.max(1, hi - lo) + 1.5f;
        int concealed = 0, refilled = 0;
        for (int f = 0; f < frags; f++) {
            if (photoGot[f]) continue;
            int e = f;
            while (e < frags && !photoGot[e]) e++;
            boolean refill = e - f >= REFILL_MIN_RUN;
            for (int k = f; refill && k < e; k++) refill = refillGot[k];
            if (refill) { // the neighbours on both sides must match between the passes
                int n = 0;
                for (int k : new int[] {f - 1, e}) {
                    if (k < 0 || k >= frags) continue;
                    if (!refillGot[k] || passDiff(k) > still) refill = false;
                    n++;
                }
                refill &= n > 0;
            }
            if (refill) {
                System.arraycopy(refillImg, f * 300, photoImg, f * 300, (e - f) * 300);
                refilled += e - f;
            } else {
                conceal(photoImg, f * 300, e * 300, e < frags ? e * 300 + 300 : e * 300);
                concealed += e - f;
            }
            f = e;
        }
        final byte[] img = photoImg;
        final int rf = refilled, cc = concealed;
        photo = PHOTO_OFF;
        photoImg = refillImg = null;
        ui.post(() -> listener.onPhoto(this, img, rf, cc));
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 255) | (b[i + 1] & 255) << 8;
    }
}
