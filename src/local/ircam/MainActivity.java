// SPDX-License-Identifier: MIT
package local.ircam;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * IR camera of the right Joy-Con. The picture comes from the root helper (IrCamera).
 * While it streams, Android gets no input from the right Joy-Con, so its buttons
 * arrive through the helper (onButtons); the left Joy-Con works as usual (dispatchKeyEvent).
 */
public class MainActivity extends Activity implements IrCamera.Listener {
    // Speeds are what a Bluetooth Joy-Con manages: ~15 ms per 300-byte fragment.
    static final int[] MODE_RES = {80, 160, 320, 40};
    static final String[] MODE_NAMES = {"Video 80×60", "Video 160×120", "Photo 320×240", "Turbo 40×30"};
    static final int[] EXPOSURES = {0, 600, 300, 150, 75, 30}; // µs, 0 = auto
    static final int MOTION_SHAKY = 150;
    // Right Joy-Con buttons: input report byte 3 and byte 4.
    static final int JC_Y = 0x01, JC_X = 0x02, JC_B = 0x04, JC_A = 0x08, JC_SR = 0x10, JC_SL = 0x20,
            JC_R = 0x40, JC_ZR = 0x80;
    static final int JC_PLUS = 0x02, JC_RSTICK = 0x04, JC_HOME = 0x10;

    SharedPreferences prefs;
    IrView view;
    TextView status, stats, motionView;
    Button modeBtn, paletteBtn, ledsBtn, expBtn, contrastBtn, rotateBtn;

    int mode, palette, rotation, expIdx;
    boolean mirror, leds = true, autoContrast = true;
    int exposure = 300, gain = 1; // current values, auto exposure changes them

    HandlerThread ctl;
    Handler ctlHandler;
    final Handler ui = new Handler();
    volatile IrCamera cam; // assigned on the control thread only
    volatile boolean resumed;
    int restarts;
    float linkLoss = -1; // share of fragments lost on Bluetooth, from the helper's "JS" packets
    boolean weakLink;

    byte[] frame = new byte[0];
    int frameW, frameH;
    final int[] hist = new int[256];
    float lo = 0, hi = 255, fps;
    long lastFrameAt, lastRender;
    int aeSkip, prevRight = -1, prevShared = -1, motionAvg;
    // Photo: wait until the exposure has settled (CAPTURE_WAIT), then IrCamera keeps exactly one pass
    // (one sensor snapshot) with the exposure frozen (CAPTURE_RUN) and calls onPhoto.
    static final int CAPTURE_OFF = 0, CAPTURE_WAIT = 1, CAPTURE_RUN = 2;
    int capture = CAPTURE_OFF, captureMotion;
    Uri lastPhoto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ircam", MODE_PRIVATE);
        mode = prefs.getInt("mode", 0);
        palette = prefs.getInt("palette", 0);
        rotation = prefs.getInt("rotation", 90);
        mirror = prefs.getBoolean("mirror", false);
        expIdx = prefs.getInt("exp", 0);
        autoContrast = prefs.getBoolean("contrast", true);
        exposure = prefs.getInt("exposure", 300);
        gain = prefs.getInt("gain", 1);
        String last = prefs.getString("lastPhoto", null);
        if (last != null) lastPhoto = Uri.parse(last);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        view.setPalette(Palettes.lut(palette));
        view.setTransform(rotation, mirror);
        updateLabels();
        ctl = new HandlerThread("ircam-ctl");
        ctl.start();
        ctlHandler = new Handler(ctl.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        restarts = 0;
        startCamera();
    }

    @Override
    protected void onPause() {
        resumed = false;
        capture = CAPTURE_OFF;
        view.setCapture(-1, null);
        ctlHandler.post(this::stopSync);
        prefs.edit().putInt("exposure", exposure).putInt("gain", gain).apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ctlHandler.post(this::stopSync);
        ctl.quitSafely(); // runs the pending stop first
        super.onDestroy();
    }

    // ---- camera session ----

    void startCamera() {
        final int res = MODE_RES[mode], e = exposure, g = gain, l = leds ? 0 : 0x30;
        final String helper = getApplicationInfo().nativeLibraryDir + "/libircam.so";
        capture = CAPTURE_OFF;
        view.setCapture(-1, null);
        view.setMessage("Connecting to the Joy-Con camera…");
        setStatus("Connecting…", 0xFFFFC107);
        ctlHandler.post(() -> {
            stopSync();
            if (!resumed) return;
            IrCamera c = new IrCamera(helper, res, e, g, l, this);
            cam = c;
            c.start();
        });
    }

    void stopSync() { // control thread
        IrCamera c = cam;
        cam = null;
        if (c != null) c.stopAndWait();
    }

    @Override
    public void onReady(IrCamera c) {
        if (c != cam) return;
        prevRight = prevShared = -1;
        restarts = 0; // it worked: a later drop gets the full set of retries again
        linkLoss = -1;
        weakLink = false;
        aeSkip = 0;
        lastFrameAt = 0;
        fps = 0;
        setStatus("● Live", 0xFF66BB6A);
        view.setMessage(null);
    }

    @Override
    public void onFragment(IrCamera c, int frag, boolean done) {
        if (c != cam) return;
        motionAvg = (motionAvg * 7 + c.motion) / 8;
        if (capture == CAPTURE_RUN && !done) {
            captureMotion = Math.max(captureMotion, c.motion);
            if (c.frags >= 64)
                view.setCapture((frag + 1) / (float) c.frags,
                        c.motion > MOTION_SHAKY ? "Hold the Joy-Con still!" : "Capturing…");
        }
        if (done) frameDone(c);
        long now = SystemClock.uptimeMillis();
        // 160×120 and 320×240 take seconds per pass: show the last whole pass, with a scan line for the new one
        boolean progressive = c.frags >= 64;
        if (done || (progressive && now - lastRender >= 50)) {
            lastRender = now;
            render(c, progressive && !done ? (frag + 1) / (float) c.frags : -1);
        }
    }

    @Override
    public void onButtons(IrCamera c, int right, int shared) {
        if (c != cam) return;
        if (prevRight < 0) { // held when the session started: not a press
            prevRight = right;
            prevShared = shared;
            return;
        }
        int pr = right & ~prevRight, ps = shared & ~prevShared;
        prevRight = right;
        prevShared = shared;
        if ((pr & (JC_A | JC_ZR | JC_SR)) != 0) shutter();
        if ((pr & (JC_X | JC_SL)) != 0) setPalette(palette + 1);
        if ((pr & JC_Y) != 0) nextMode();
        if ((pr & JC_R) != 0) toggleLeds();
        if ((ps & JC_PLUS) != 0) nextExposure(1);
        if ((ps & JC_RSTICK) != 0) toggleContrast();
        if ((pr & JC_B) != 0) finish();
        if ((ps & JC_HOME) != 0) goHome();
    }

    @Override
    public void onLink(IrCamera c, float loss, int maxGapMs) {
        if (c != cam) return;
        linkLoss = linkLoss < 0 ? loss : linkLoss * 0.6f + loss * 0.4f;
        boolean weak = linkLoss > 0.25f || maxGapMs > 500;
        if (weak != weakLink && capture == CAPTURE_OFF) {
            weakLink = weak;
            if (weak) setStatus("● Weak signal", 0xFFFFA726);
            else setStatus("● Live", 0xFF66BB6A);
        }
    }

    @Override
    public void onStopped(IrCamera c, int code, String err) {
        if (c != cam || c.stopping) return; // stopped on purpose
        capture = CAPTURE_OFF;
        view.setCapture(-1, null);
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        boolean noRoot = e.contains("denied") || e.contains("daemon");
        boolean transient_ = !noRoot && (code == 20 || (code >= 1 && code <= 9));
        if (resumed && transient_ && restarts++ < 3) {
            setStatus("Reconnecting…", 0xFFFFC107);
            ui.postDelayed(() -> {
                if (resumed && cam == c) startCamera();
            }, 700L * restarts);
            return;
        }
        String msg;
        if (code == 10) {
            msg = "Right Joy-Con not found.\nThe camera works when it is detached\nand connected over Bluetooth.";
        } else if (e.contains("daemon")) {
            msg = "Root (Magisk) is not responding.\nRestart the console.";
        } else if (code == 11 || e.contains("denied")) {
            msg = "No root access to the Joy-Con.\nGrant root to IR Camera in Magisk.";
        } else if (code >= 1 && code <= 9) {
            msg = "The Joy-Con camera is not responding (step " + code + ").";
        } else {
            msg = "The camera stopped (code " + code + ")" + (err == null || err.isEmpty() ? "" : "\n" + err);
        }
        view.setMessage(msg + "\n\nPress A or ● to retry");
        setStatus("Stopped", 0xFFE57373);
    }

    // ---- frames ----

    void render(IrCamera c, float scan) {
        if (frame.length != c.live.length) frame = new byte[c.live.length];
        synchronized (c.live) {
            System.arraycopy(c.done, 0, frame, 0, frame.length);
        }
        frameW = c.w;
        frameH = c.h;
        view.setFrame(frame, c.w, c.h, lo, hi, scan);
    }

    void rerender() {
        if (frameW > 0) view.setFrame(frame, frameW, frameH, lo, hi, -1);
    }

    void frameDone(IrCamera c) {
        long now = SystemClock.uptimeMillis();
        if (lastFrameAt > 0) {
            float f = 1000f / Math.max(1, now - lastFrameAt);
            fps = fps == 0 ? f : fps * 0.7f + f * 0.3f;
        }
        lastFrameAt = now;
        int n = c.w * c.h;
        Arrays.fill(hist, 0);
        synchronized (c.live) {
            for (int i = 0; i < n; i++) hist[c.done[i] & 255]++;
        }
        if (autoContrast) { // stretch 0.5..99.5 percentiles, smoothed so it doesn't flicker
            int pLo = percentile(n * 0.005), pHi = percentile(n * 0.995);
            if (pHi - pLo < 24) {
                pLo = Math.max(0, (pLo + pHi) / 2 - 12);
                pHi = pLo + 24;
            }
            lo = lo * 0.6f + pLo * 0.4f;
            hi = hi * 0.6f + pHi * 0.4f;
        }
        if (capture == CAPTURE_WAIT) {
            if (aeSkip > 0) aeSkip--; // the next pass still has the old exposure: one more
            else {
                capture = CAPTURE_RUN;
                captureMotion = 0;
                c.takePhoto();
                if (c.frags >= 64) view.setCapture(0, "Capturing…");
            }
        }
        if (capture == CAPTURE_OFF) autoExpose(c, n);
        updateStats(c);
    }

    int percentile(double count) {
        int acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc > count) return v;
        }
        return 255;
    }

    /** Aims the mean at ~105 with exposure (≤600 µs), then digital gain (≤16). */
    void autoExpose(IrCamera c, int n) {
        if (EXPOSURES[expIdx] != 0) return;
        if (aeSkip > 0) { // the frame after a change is partly old exposure
            aeSkip--;
            return;
        }
        long sum = 0;
        int sat = 0;
        for (int v = 0; v < 256; v++) {
            sum += (long) v * hist[v];
            if (v >= 250) sat += hist[v];
        }
        double mean = sum / (double) n, satFrac = sat / (double) n;
        if (mean > 75 && mean < 140 && satFrac < 0.02) return;
        double ratio = 105 / Math.max(mean, 1.5);
        if (satFrac >= 0.02) ratio = Math.min(ratio, 0.6);
        ratio = Math.max(0.3, Math.min(3, ratio));
        double total = exposure * (double) gain * ratio;
        int e, g;
        if (total <= 600) {
            g = 1;
            e = (int) Math.max(5, Math.round(total));
        } else {
            e = 600;
            g = (int) Math.max(1, Math.min(16, Math.round(total / 600)));
        }
        if (e == exposure && g == gain) return;
        exposure = e;
        gain = g;
        aeSkip = 1;
        c.send("e " + e + " " + g);
    }

    void updateStats(IrCamera c) {
        String speed = fps > 0 && fps < 1 ? String.format(Locale.US, "%.1f s/frame", 1 / fps)
                : String.format(Locale.US, "%.1f fps", fps);
        stats.setText(c.w + "×" + c.h + " · " + speed + "\nExposure " + exposure + " µs\nGain ×" + gain
                + "\nBrightness " + (c.avg * 100 / 255) + "%"
                + (linkLoss < 0 ? "" : "\nLink " + Math.round(100 * (1 - linkLoss)) + "%"));
        boolean shaky = motionAvg > MOTION_SHAKY;
        motionView.setText((shaky ? "Joy-Con moving" : "Joy-Con still") + " (" + motionAvg + ")");
        motionView.setTextColor(shaky ? 0xFFFFA726 : 0xFF81C784);
    }

    // ---- actions ----

    void shutter() {
        IrCamera c = cam;
        if (c == null || !c.alive) { // stopped on an error: try again
            restarts = 0;
            startCamera();
            return;
        }
        if (!c.ready || capture != CAPTURE_OFF) return;
        capture = CAPTURE_WAIT; // starts at the next pass, see frameDone
        if (c.frags >= 64) view.setCapture(0, "Waiting for a new frame…");
    }

    @Override
    public void onPhoto(IrCamera c, byte[] img, int refilled, int concealed) {
        if (c != cam || capture != CAPTURE_RUN) return;
        capture = CAPTURE_OFF;
        view.setCapture(-1, null);
        savePhoto(c, img);
    }

    void savePhoto(IrCamera c, byte[] g) {
        final Bitmap bmp = view.export(g, c.w, c.h, lo, hi, 960);
        final boolean shaky = captureMotion > MOTION_SHAKY;
        final String name = "IR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        view.flash();
        new Thread(() -> {
            Uri uri;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JoyConIR");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } catch (Exception e) {
                uri = null;
            }
            final Uri saved = uri;
            ui.post(() -> {
                if (saved == null) {
                    toast("Could not save the photo");
                    return;
                }
                lastPhoto = saved;
                prefs.edit().putString("lastPhoto", saved.toString()).apply();
                toast(shaky ? "Saved, but the Joy-Con moved: it may be blurred"
                        : "Saved: Pictures/JoyConIR/" + name);
            });
        }, "ircam-save").start();
    }

    void nextMode() {
        mode = (mode + 1) % MODE_RES.length;
        prefs.edit().putInt("mode", mode).apply();
        updateLabels();
        restarts = 0;
        startCamera();
    }

    void setPalette(int i) {
        palette = (i + Palettes.NAMES.length) % Palettes.NAMES.length;
        prefs.edit().putInt("palette", palette).apply();
        view.setPalette(Palettes.lut(palette));
        rerender();
        updateLabels();
    }

    void toggleLeds() {
        leds = !leds;
        IrCamera c = cam;
        if (c != null) c.send("l " + (leds ? 0 : 0x30));
        updateLabels();
    }

    void nextExposure(int dir) {
        expIdx = (expIdx + dir + EXPOSURES.length) % EXPOSURES.length;
        if (EXPOSURES[expIdx] != 0) {
            exposure = EXPOSURES[expIdx];
            gain = 1;
            IrCamera c = cam;
            if (c != null) c.send("e " + exposure + " " + gain);
        }
        aeSkip = 1;
        prefs.edit().putInt("exp", expIdx).apply();
        updateLabels();
    }

    void toggleContrast() {
        autoContrast = !autoContrast;
        if (!autoContrast) {
            lo = 0;
            hi = 255;
        }
        prefs.edit().putBoolean("contrast", autoContrast).apply();
        rerender();
        updateLabels();
    }

    void rotate() {
        rotation = (rotation + 90) % 360;
        prefs.edit().putInt("rotation", rotation).apply();
        view.setTransform(rotation, mirror);
        updateLabels();
    }

    void toggleMirror() {
        mirror = !mirror;
        prefs.edit().putBoolean("mirror", mirror).apply();
        view.setTransform(rotation, mirror);
        updateLabels();
    }

    void openGallery() {
        if (lastPhoto == null) {
            toast("No photos yet");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastPhoto, "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            toast("No app to view photos");
        }
    }

    void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // Left Joy-Con (and the right one while the helper isn't running) come as key events.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (down) shutter();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) setPalette(palette + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) setPalette(palette - 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (down) nextMode();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) toggleLeds();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) nextExposure(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) nextExposure(-1);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- UI ----

    View buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int longSide = Math.max(dm.widthPixels, dm.heightPixels), shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int side = Math.max(dp(140), (longSide - shortSide * 4 / 3) / 2);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout left = column();
        TextView title = text("IR Camera", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        status = text("", 13, 0xFFFFC107);
        left.addView(status);
        stats = text("", 12, 0xFFB0BEC5);
        stats.setPadding(0, dp(10), 0, 0);
        left.addView(stats);
        motionView = text("", 12, 0xFF81C784);
        motionView.setPadding(0, dp(6), 0, 0);
        left.addView(motionView);
        left.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        left.addView(text("A: photo\nX: palette\nY: mode\nR: IR light\n+: exposure\nB: exit", 12, 0xFF78909C));
        TextView hint = text("The camera is at the bottom end of the right Joy-Con; its IR light reaches about a meter.", 11, 0xFF607D8B);
        hint.setPadding(0, dp(10), 0, 0);
        left.addView(hint);
        root.addView(left, new LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT));

        view = new IrView(this);
        root.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout right = column();
        Button shot = new Button(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0xFFE53935);
        ring.setStroke(dp(4), Color.WHITE);
        shot.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), ring, null));
        shot.setFocusable(false);
        shot.setContentDescription("Photo");
        shot.setOnClickListener(v -> shutter());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(64), dp(64));
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = dp(4);
        right.addView(shot, sp);
        modeBtn = button(right, v -> nextMode());
        paletteBtn = button(right, v -> setPalette(palette + 1));
        ledsBtn = button(right, v -> toggleLeds());
        expBtn = button(right, v -> nextExposure(1));
        contrastBtn = button(right, v -> toggleContrast());
        rotateBtn = button(right, v -> rotate());
        rotateBtn.setOnLongClickListener(v -> {
            toggleMirror();
            return true;
        });
        button(right, v -> openGallery()).setText("Gallery");
        root.addView(right, new LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    void updateLabels() {
        modeBtn.setText("Mode\n" + MODE_NAMES[mode]);
        paletteBtn.setText("Palette\n" + Palettes.NAMES[palette]);
        ledsBtn.setText("IR light\n" + (leds ? "on" : "off"));
        expBtn.setText("Exposure\n" + (EXPOSURES[expIdx] == 0 ? "auto" : EXPOSURES[expIdx] + " µs"));
        contrastBtn.setText("Contrast\n" + (autoContrast ? "auto" : "raw"));
        rotateBtn.setText("Rotation\n" + rotation + "°" + (mirror ? " ⇋" : ""));
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(10), dp(12), dp(10), dp(12));
        return l;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    Button button(LinearLayout parent, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setFocusable(false);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), dp(5), dp(6), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E232B);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF39404C);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
