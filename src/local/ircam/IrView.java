// SPDX-License-Identifier: MIT
package local.ircam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Draws the IR frame through a palette, fitted and rotated, with the scan line and capture progress. */
class IrView extends View {
    private Bitmap bmp;
    private int[] px;
    private int[] palette = Palettes.lut(0);
    private final int[] comb = new int[256];
    private int rotation;
    private boolean mirror;
    private float scan = -1, capture = -1;
    private String message, captureText;
    private long flashUntil;
    private final float d;
    private final Matrix m = new Matrix();
    private final float[] pts = new float[4];
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();

    IrView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        line.setStrokeWidth(2 * d);
        line.setColor(0xCCFFFFFF);
        text.setColor(Color.WHITE);
        text.setTextSize(17 * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(4 * d, 0, 0, Color.BLACK);
    }

    void setPalette(int[] lut) {
        palette = lut;
    }

    void setTransform(int rotation, boolean mirror) {
        this.rotation = rotation;
        this.mirror = mirror;
        invalidate();
    }

    void setMessage(String msg) {
        message = msg;
        invalidate();
    }

    void setCapture(float progress, String label) {
        capture = progress;
        captureText = label;
        invalidate();
    }

    void flash() {
        flashUntil = SystemClock.uptimeMillis() + 250;
        invalidate();
    }

    /** Gray -> ARGB through the palette, stretching lo..hi to the full range. */
    static void colorize(byte[] gray, int n, int[] out, int[] palette, float lo, float hi, int[] comb) {
        float k = 255f / Math.max(1f, hi - lo);
        for (int v = 0; v < 256; v++) {
            int i = Math.round((v - lo) * k);
            comb[v] = palette[i < 0 ? 0 : i > 255 ? 255 : i];
        }
        for (int i = 0; i < n; i++) out[i] = comb[gray[i] & 255];
    }

    void setFrame(byte[] gray, int w, int h, float lo, float hi, float scanPos) {
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            px = new int[w * h];
        }
        colorize(gray, w * h, px, palette, lo, hi, comb);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        scan = scanPos;
        invalidate();
    }

    /** Picture for saving: palette, upscaled (bilinear) and rotated like on screen. */
    Bitmap export(byte[] gray, int w, int h, float lo, float hi, int longSide) {
        int[] out = new int[w * h];
        colorize(gray, w * h, out, palette, lo, hi, new int[256]);
        Bitmap src = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        int k = Math.max(1, longSide / Math.max(w, h));
        Bitmap big = Bitmap.createScaledBitmap(src, w * k, h * k, true);
        if (rotation == 0 && !mirror) return big;
        Matrix t = new Matrix();
        if (mirror) t.postScale(-1, 1);
        t.postRotate(rotation);
        return Bitmap.createBitmap(big, 0, 0, big.getWidth(), big.getHeight(), t, true);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        int vw = getWidth(), vh = getHeight();
        if (bmp != null) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            boolean side = rotation % 180 != 0;
            float s = Math.min(vw / (float) (side ? h : w), vh / (float) (side ? w : h));
            m.reset();
            m.postTranslate(-w / 2f, -h / 2f);
            if (mirror) m.postScale(-1, 1);
            m.postRotate(rotation);
            m.postScale(s, s);
            m.postTranslate(vw / 2f, vh / 2f);
            c.drawBitmap(bmp, m, bmpPaint);
            if (scan > 0 && scan < 1) {
                pts[0] = 0;
                pts[1] = scan * h;
                pts[2] = w;
                pts[3] = scan * h;
                m.mapPoints(pts);
                c.drawLine(pts[0], pts[1], pts[2], pts[3], line);
            }
        }
        if (capture >= 0) {
            float bw = vw * 0.6f, x0 = (vw - bw) / 2, y = vh - 28 * d;
            fill.setColor(0x99000000);
            c.drawRect(x0 - 10 * d, y - 30 * d, x0 + bw + 10 * d, y + 14 * d, fill);
            fill.setColor(0x55FFFFFF);
            c.drawRect(x0, y, x0 + bw, y + 6 * d, fill);
            fill.setColor(0xFFE53935);
            c.drawRect(x0, y, x0 + bw * capture, y + 6 * d, fill);
            if (captureText != null) c.drawText(captureText, vw / 2f, y - 10 * d, text);
        }
        if (message != null) {
            String[] lines = message.split("\n");
            float lh = text.getTextSize() * 1.35f, y = vh / 2f - (lines.length - 1) * lh / 2;
            if (bmp != null) {
                fill.setColor(0xAA000000);
                c.drawRect(0, y - lh, vw, y + lines.length * lh - lh / 2, fill);
            }
            for (String l : lines) {
                c.drawText(l, vw / 2f, y, text);
                y += lh;
            }
        }
        long left = flashUntil - SystemClock.uptimeMillis();
        if (left > 0) {
            fill.setColor(Color.argb((int) (200 * left / 250f), 255, 255, 255));
            c.drawRect(0, 0, vw, vh, fill);
            postInvalidateOnAnimation();
        }
    }
}
