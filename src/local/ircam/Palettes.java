// SPDX-License-Identifier: MIT
package local.ircam;

/** False-colour lookup tables: 256 ARGB entries built from gradient stops. */
final class Palettes {
    static final String[] NAMES = {"Night", "Thermal", "B/W", "Rainbow", "Negative"};

    // {position 0..255, r, g, b}
    private static final int[][][] STOPS = {
            {{0, 0, 6, 0}, {110, 18, 120, 30}, {200, 110, 240, 110}, {255, 225, 255, 215}},
            {{0, 0, 0, 12}, {40, 40, 0, 110}, {90, 150, 0, 150}, {140, 225, 40, 40},
                    {190, 255, 145, 0}, {230, 255, 225, 70}, {255, 255, 255, 255}},
            {{0, 0, 0, 0}, {255, 255, 255, 255}},
            {{0, 48, 18, 59}, {33, 70, 107, 227}, {64, 40, 187, 236}, {97, 49, 242, 153},
                    {128, 162, 252, 60}, {161, 237, 208, 58}, {191, 251, 128, 34},
                    {224, 208, 48, 7}, {255, 122, 4, 3}},
            {{0, 255, 255, 255}, {255, 0, 0, 0}},
    };

    private Palettes() {
    }

    static int[] lut(int i) {
        int[][] s = STOPS[i];
        int[] lut = new int[256];
        for (int k = 0; k + 1 < s.length; k++) {
            int[] a = s[k], b = s[k + 1];
            for (int v = a[0]; v <= b[0]; v++) {
                float t = (v - a[0]) / (float) (b[0] - a[0]);
                lut[v] = 0xFF000000 | mix(a[1], b[1], t) << 16 | mix(a[2], b[2], t) << 8 | mix(a[3], b[3], t);
            }
        }
        return lut;
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
