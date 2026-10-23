// SPDX-License-Identifier: MIT
// ircam — reads the IR camera of the right Joy-Con through /dev/hidraw (root, Bluetooth).
// Protocol: CTCaer's jc_toolkit (jctool.cpp: ir_sensor / get_raw_ir_image, MIT) and dekuNukem's
// Nintendo_Switch_Reverse_Engineering notes; see NOTICE.
//
//   ircam [-d /dev/hidrawN] [-r 320|160|80|40] [-e exposure_us] [-g gain] [-l leds] [-i intensity]
//         [-f] [-a] [-n frames] [-o file.pgm | -o -] [-x] [-v] [-m 0|1] [-w 0|1]
//   -d    : hidraw device (default: the first right Joy-Con, 057E:2007)
//   -r    : resolution; the Joy-Con sends 300-byte fragments every ~15 ms, so 320x240 takes ~3.4 s,
//           160x120 ~0.8 s, 80x60 ~0.2 s, 40x30 ~60 ms
//   -l    : IR LED register 0x0010 (0x00 both groups, 0x10 / 0x20 one group, 0x30 off)
//   -f    : external light filter, -a : simple auto exposure, -n 0 : stream until stopped
//   -m 1  : (default) keep every fragment as it arrives; lost ones refresh on the next pass.
//   -m 0  : jc_toolkit's strict order with resend requests (stalls for seconds on a lossy link).
//   -w 0  : keep Wi-Fi power save as it is (default: off while streaming, see wifi_ps_*).
//   -v    : log; -v prints link statistics every 5 s, -vvvv a per-report timing trace
//   -o -  : binary stream to stdout, 16-byte packets (little endian):
//           "JR" res:u16 max_frag:u8                                   camera ready
//           "JF" frag max_frag avg end white:u16 noise:u16 exp:u16 motion:u16 gain 0, then 300 bytes
//                (end = 1 on the fragment that ends a frame)
//           "JB" buttons[3] (input report bytes 3..5)                  right Joy-Con buttons changed
//           "JS" reports:u16 frags:u16 lost:u16 maxgap_ms:u16 span_ms:u16  link stats, about every second
//           stdin accepts live commands: "e <us> [gain]", "g <gain>", "l <leds>", "i <0-16>", "a <0|1>", "q".
//           The kernel driver ignores 0x31 reports, so while streaming the right Joy-Con's buttons
//           only reach the app through "JB".
//   -x    : only reset the Joy-Con (MCU off, report mode 0x30) and exit.
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define OUT_LEN 49
#define IN_LEN 0x170

static int fd = -1;
static int lossy = 1;  // -m 1: take every fragment as it comes, never wait for resends (-m 0: jc_toolkit's strict order)
static uint8_t tcount;
static volatile sig_atomic_t stop_req;
static int verbose;

// Camera settings
static int res = 320, max_frag = 0xff, res_reg = 0x00;
static int exposure_us = 300, gain = 1, leds = 0x00, intensity = 16, exfilter = 0, autoexp = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

static uint8_t crc8(const uint8_t *p, int n) {  // CRC-8, poly 0x07 (same table as jc_toolkit)
    uint8_t c = 0;
    for (int i = 0; i < n; i++) {
        c ^= p[i];
        for (int b = 0; b < 8; b++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1);
    }
    return c;
}

static void hexdump(const char *tag, const uint8_t *b, int n) {
    fprintf(stderr, "%s [%d]:", tag, n);
    for (int i = 0; i < n && i < 64; i++) fprintf(stderr, " %02x", b[i]);
    fprintf(stderr, "\n");
}

static long long t_start;
static long long now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000 - t_start;
}

static int send_out(uint8_t *b) {
    b[1] = tcount++ & 0xF;
    if (verbose > 3) fprintf(stderr, "%8lld TX %02x %02x ack=%d\n", now_us(), b[0], b[10], b[14]);
    if (verbose > 1) hexdump(">>", b, OUT_LEN);
    if (write(fd, b, OUT_LEN) != OUT_LEN) {
        fprintf(stderr, "write: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static int recv_in(uint8_t *b, int timeout_ms) {
    struct pollfd p = {fd, POLLIN, 0};
    if (timeout_ms < 0) timeout_ms = 0;
    if (poll(&p, 1, timeout_ms) <= 0) return 0;
    int r = read(fd, b, IN_LEN);
    if (r < 0) {
        if (errno == EINTR || errno == EAGAIN) return 0;
        fprintf(stderr, "read: %s\n", strerror(errno));
        return -1;
    }
    if (verbose > 2) hexdump("<<", b, r);
    return r;
}

typedef int (*match_fn)(const uint8_t *r, int n);

// Sends `out`, waits up to wait_ms for a reply accepted by `m`, retries `tries` times.
static int transact(const char *what, uint8_t *out, match_fn m, int tries, int wait_ms) {
    uint8_t r[IN_LEN];
    for (int t = 0; t < tries && !stop_req; t++) {
        if (send_out(out) < 0) return -1;
        long long dl = now_ms() + wait_ms;
        while (now_ms() < dl) {
            int n = recv_in(r, (int)(dl - now_ms()));
            if (n < 0) return -1;
            if (n > 0 && m(r, n)) {
                if (verbose) fprintf(stderr, "ok: %s\n", what);
                return 0;
            }
            if (n > 0 && verbose > 1 && r[0] != 0x30 && !(r[0] == 0x31 && r[49] == 0xff)) hexdump("  ?", r, n);
        }
    }
    fprintf(stderr, "failed: %s\n", what);
    return -1;
}

static void subcmd(uint8_t *b, uint8_t sc, const uint8_t *args, int n) {
    memset(b, 0, OUT_LEN);
    b[0] = 0x01;
    b[10] = sc;
    if (n) memcpy(b + 11, args, n);
}

static void mcu_req(uint8_t *b, uint8_t a, uint8_t c) {  // output report 0x11
    memset(b, 0, OUT_LEN);
    b[0] = 0x11;
    b[10] = a;
    b[11] = c;
    if (a == 0x03) {
        b[47] = crc8(b + 11, 36);
        b[48] = 0xff;
    }
}

static int m_ack03(const uint8_t *r, int n) { return n > 14 && r[0] == 0x21 && r[13] == 0x80 && r[14] == 0x03; }
static int m_ack22(const uint8_t *r, int n) { return n > 14 && r[0] == 0x21 && r[13] == 0x80 && r[14] == 0x22; }
static int m_mcu_standby(const uint8_t *r, int n) { return n > 56 && r[0] == 0x31 && r[49] == 0x01 && r[56] == 0x01; }
static int m_mcu_ir(const uint8_t *r, int n) { return n > 56 && r[0] == 0x31 && r[49] == 0x01 && r[56] == 0x05; }
static int m_setmcu(const uint8_t *r, int n) { return n > 15 && r[0] == 0x21 && r[14] == 0x21 && r[15] == 0x01; }
static int m_irmode(const uint8_t *r, int n) { return n > 15 && r[0] == 0x21 && r[14] == 0x21 && r[15] == 0x0b; }
static int m_irstat7(const uint8_t *r, int n) { return n > 51 && r[0] == 0x31 && r[49] == 0x13 && r[50] == 0x00 && r[51] == 0x07; }
static int m_regs1(const uint8_t *r, int n) {
    return (n > 17 && r[0] == 0x21 && r[15] == 0x13 && r[16] == 0x00 && r[17] == 0x07) || m_irstat7(r, n);
}
static int m_regs2(const uint8_t *r, int n) {
    return m_irstat7(r, n) || (n > 49 && r[0] == 0x31 && r[49] == 0x23) ||
           (n > 15 && r[0] == 0x21 && r[14] == 0x21 && r[15] == 0x13);  // or the subcommand ack
}

static int set_report_mode(uint8_t mode) {
    uint8_t b[OUT_LEN], a = mode;
    subcmd(b, 0x03, &a, 1);
    return transact(mode == 0x31 ? "report mode 0x31" : "report mode 0x30", b, m_ack03, 8, 300);
}

static int set_mcu_power(int on) {
    uint8_t b[OUT_LEN], a = on ? 1 : 0;
    subcmd(b, 0x22, &a, 1);
    return transact(on ? "MCU on" : "MCU off", b, m_ack22, 8, 300);
}

// Subcommand 0x21 0x23 0x04: write up to 9 IR sensor registers {page, offset, value}.
static void regs_buf(uint8_t *b, const uint8_t (*regs)[3], int n) {
    uint8_t a[3 + 27] = {0x23, 0x04, (uint8_t)n};
    for (int i = 0; i < n; i++) memcpy(a + 3 + i * 3, regs[i], 3);
    subcmd(b, 0x21, a, 3 + n * 3);
    b[48] = crc8(b + 12, 36);
}

static int exposure_reg(void) { return exposure_us * 31200 / 1000; }

static void send_live_regs(void) {  // exposure / gain / leds while streaming, then "finalize"
    int e = exposure_reg();
    uint8_t regs[][3] = {
        {0x01, 0x30, (uint8_t)(e & 0xff)},
        {0x01, 0x31, (uint8_t)(e >> 8)},
        {0x01, 0x2e, (uint8_t)((gain & 0x0f) << 4)},
        {0x01, 0x2f, (uint8_t)((gain & 0xf0) >> 4)},
        {0x00, 0x10, (uint8_t)leds},
        {0x00, 0x11, (uint8_t)(intensity > 15 ? 15 : intensity)},
        {0x00, 0x12, (uint8_t)intensity},
        {0x00, 0x07, 0x01},
    };
    uint8_t b[OUT_LEN];
    regs_buf(b, regs, 8);
    send_out(b);
}

static int ir_start(void) {
    uint8_t b[OUT_LEN];
    if (set_report_mode(0x31)) return 1;
    if (set_mcu_power(1)) return 2;
    mcu_req(b, 0x01, 0);
    if (transact("MCU standby", b, m_mcu_standby, 20, 200)) return 3;

    uint8_t a_mode[] = {0x21, 0x00, 0x05};  // MCU mode 5 = IR
    subcmd(b, 0x21, a_mode, sizeof a_mode);
    b[48] = crc8(b + 12, 36);
    if (transact("MCU mode IR", b, m_setmcu, 8, 300)) return 4;
    mcu_req(b, 0x01, 0);
    if (transact("MCU is IR", b, m_mcu_ir, 20, 200)) return 5;

    // IR mode 7 = image transfer, number of fragments, required MCU FW 5.18
    uint8_t a_ir[] = {0x23, 0x01, 0x07, (uint8_t)max_frag, 0x00, 0x05, 0x00, 0x18};
    subcmd(b, 0x21, a_ir, sizeof a_ir);
    b[48] = crc8(b + 12, 36);
    if (transact("IR mode 7", b, m_irmode, 8, 300)) return 6;
    mcu_req(b, 0x03, 0x02);
    if (transact("IR status", b, m_irstat7, 8, 300)) return 7;

    int e = exposure_reg();
    uint8_t regs1[][3] = {
        {0x00, 0x2e, (uint8_t)res_reg},                // resolution (binning/skipping)
        {0x01, 0x30, (uint8_t)(e & 0xff)},             // exposure LSB
        {0x01, 0x31, (uint8_t)(e >> 8)},               // exposure MSB
        {0x01, 0x32, 0x00},                            // manual exposure
        {0x00, 0x10, (uint8_t)leds},                   // IR LED groups
        {0x01, 0x2e, (uint8_t)((gain & 0x0f) << 4)},   // digital gain LSB
        {0x01, 0x2f, (uint8_t)((gain & 0xf0) >> 4)},   // digital gain MSB
        {0x00, 0x0e, (uint8_t)(exfilter ? 0x03 : 0)},  // external light filter
        {0x01, 0x43, 0xc8},                            // white pixel threshold
    };
    int ok = 0;
    for (int t = 0; t < 8 && !ok && !stop_req; t++) {
        uint8_t s[OUT_LEN], r[IN_LEN];
        regs_buf(b, regs1, 9);
        send_out(b);
        mcu_req(s, 0x03, 0x02);
        send_out(s);
        long long dl = now_ms() + 300;
        while (!ok && now_ms() < dl) {
            int n = recv_in(r, (int)(dl - now_ms()));
            if (n < 0) return 8;
            if (n > 0 && m_regs1(r, n)) ok = 1;
        }
    }
    if (!ok) { fprintf(stderr, "failed: IR registers 1\n"); return 8; }

    uint8_t regs2[][3] = {
        {0x00, 0x11, (uint8_t)(intensity > 15 ? 15 : intensity)},  // far LEDs intensity (max 15)
        {0x00, 0x12, (uint8_t)intensity},                          // near LEDs intensity (max 16)
        {0x00, 0x2d, 0x00},                                        // flip
        {0x01, 0x67, 0x01},                                        // denoise on
        {0x01, 0x68, 0x23},                                        // edge smoothing
        {0x01, 0x69, 0x44},                                        // color interpolation
        {0x00, 0x04, (uint8_t)(res == 40 ? 0x2d : 0x32)},          // buffer update time
        {0x00, 0x07, 0x01},                                        // finalize
    };
    regs_buf(b, regs2, 8);
    if (transact("IR registers 2", b, m_regs2, 8, 300)) return 9;
    return 0;
}

static void ir_stop(void) {
    uint8_t b[OUT_LEN], a = 0;
    subcmd(b, 0x22, &a, 1);  // MCU off (also turns the IR LEDs off)
    transact("MCU off", b, m_ack22, 5, 300);
    uint8_t a30 = 0x30;  // what hid-nintendo expects; without it the right Joy-Con stays dead for Android
    subcmd(b, 0x03, &a30, 1);
    transact("report mode 0x30", b, m_ack03, 15, 300);
}

// ---- Wi-Fi power save ----
// The Switch's BCM4356 shares one radio between Wi-Fi and Bluetooth. With Wi-Fi power save on, the chip
// goes deaf to Bluetooth for 70-100 ms every ~640 ms and the Joy-Con's reports in that window are lost
// (5-10% of the fragments). Turned off while streaming, restored on exit; -w 0 leaves it alone.
static int wifi_tweak = 1, wifi_ps_was_on;

static int wifi_ps_on(void) {
    FILE *p = popen("/system/bin/iw dev wlan0 get power_save 2>/dev/null", "r");
    char buf[64] = "";
    if (!p) return 0;
    if (!fgets(buf, sizeof buf, p)) buf[0] = 0;
    pclose(p);
    return strstr(buf, ": on") != NULL;
}

static void wifi_ps_set(int on) {
    if (system(on ? "/system/bin/iw dev wlan0 set power_save on >/dev/null 2>&1"
                  : "/system/bin/iw dev wlan0 set power_save off >/dev/null 2>&1") && verbose)
        fprintf(stderr, "iw: could not set Wi-Fi power save\n");
}

static void wifi_ps_hold_off(long long t) {  // Android may turn it back on (screen/idle changes): recheck
    static long long last;
    if (!wifi_ps_was_on || t - last < 10000) return;
    last = t;
    if (wifi_ps_on()) {
        if (verbose) fprintf(stderr, "Wi-Fi power save came back on: off again\n");
        wifi_ps_set(0);
    }
}

// ---- output ----
static const char *out_path;
static int stream_out;

static void write_all(int ofd, const void *p, size_t n) {
    const uint8_t *c = p;
    while (n) {
        ssize_t w = write(ofd, c, n);
        if (w <= 0) {
            if (w < 0 && errno == EINTR) continue;
            if (verbose) fprintf(stderr, "stdout write failed: %s\n", strerror(errno));
            stop_req = 1;
            return;
        }
        c += w;
        n -= (size_t)w;
    }
}

static void emit_frame(const uint8_t *img, const uint8_t *r, int ms) {
    int w = res, h = res * 3 / 4;
    int white = r[55] | r[56] << 8, noise = r[57] | r[58] << 8;
    if (out_path) {
        FILE *f = fopen(out_path, "wb");
        if (f) {
            fprintf(f, "P5\n%d %d\n255\n", w, h);
            fwrite(img, 1, (size_t)(w * h), f);
            fclose(f);
        }
    }
    if (verbose) fprintf(stderr, "frame %dx%d %d ms, avg %d, white %d, noise %d, exp %d us\n", w, h, ms, r[53], white, noise, exposure_us);
}

static void emit_packet(const uint8_t *hd, const uint8_t *payload, size_t n) {
    write_all(1, hd, 16);
    if (n) write_all(1, payload, n);
}

// Gyro activity: sum of |gyro - bias| over the 3 IMU samples of a report, bias = slow running mean.
static int gyro_bias[3], bias_init;
static int imu_motion(const uint8_t *r) {
    int m = 0;
    for (int s = 0; s < 3; s++) {
        const uint8_t *g = r + 13 + s * 12 + 6;
        int sum = 0;
        for (int a = 0; a < 3; a++) {
            int v = (int16_t)(g[a * 2] | g[a * 2 + 1] << 8) * 16;
            if (!bias_init) gyro_bias[a] = v;
            gyro_bias[a] += (v - gyro_bias[a]) / 512;
            sum += abs(v - gyro_bias[a]) / 16;
        }
        bias_init = 1;
        if (sum > m) m = sum;
    }
    return m;
}

static void auto_exposure(const uint8_t *r) {  // jc_toolkit's heuristic on the % of saturated pixels
    int max_pixels = (max_frag + 1) * 300;
    int white_pct = (r[55] | r[56] << 8) * 100 / max_pixels;
    int e = exposure_us;
    if (white_pct == 0) e += 10;
    else if (white_pct > 5) e -= (white_pct / 4) * 20;
    if (e < 0) e = 0;
    if (e > 600) e = 600;
    if (e != exposure_us) {
        exposure_us = e;
        send_live_regs();
    }
}

static void handle_cmd(char *line) {
    if (verbose) fprintf(stderr, "cmd: '%s'\n", line);
    char c;
    int v = 0, v2 = 0;
    if (sscanf(line, " %c %d %d", &c, &v, &v2) < 1) return;
    switch (c) {
        case 'q': stop_req = 1; return;
        case 'e':
            exposure_us = v < 0 ? 0 : v > 600 ? 600 : v;
            if (v2 > 0) gain = v2 > 0xff ? 0xff : v2;
            autoexp = 0;
            break;
        case 'g': gain = v < 1 ? 1 : v > 0xff ? 0xff : v; break;
        case 'l': leds = v & 0xff; break;
        case 'i': intensity = v < 0 ? 0 : v > 16 ? 16 : v; break;
        case 'a': autoexp = v != 0; return;
        default: return;
    }
    send_live_regs();
}

// Link statistics, printed every 5 s with -v (reset after each print).
static struct {
    int reports, empty, frags, repeats, skips, recovered, stragglers, nudges, stuck, acks, maxgap;
} st;
static long long st_t0, st_last_rx;
// The same per second for the app's link indicator ("JS" packet).
static struct { int reports, frags, skips, maxgap; } lk;
static long long lk_t0;

static void link_tick(long long t) {
    if (t - lk_t0 < 1000) return;
    uint8_t hd[16] = {'J', 'S', (uint8_t)lk.reports, (uint8_t)(lk.reports >> 8), (uint8_t)lk.frags, (uint8_t)(lk.frags >> 8),
                      (uint8_t)lk.skips, (uint8_t)(lk.skips >> 8), (uint8_t)lk.maxgap, (uint8_t)(lk.maxgap >> 8),
                      (uint8_t)(t - lk_t0), (uint8_t)((t - lk_t0) >> 8)};
    emit_packet(hd, NULL, 0);
    memset(&lk, 0, sizeof lk);
    lk_t0 = t;
}

static void stats_tick(long long t, int force) {
    if (!verbose || (!force && t - st_t0 < 5000)) return;
    fprintf(stderr, "stat %lld ms: rx %d (empty %d) frags %d rep %d skip %d recov %d strag %d nudge %d stuck %d acks %d maxgap %d\n",
            t - st_t0, st.reports, st.empty, st.frags, st.repeats, st.skips, st.recovered, st.stragglers, st.nudges,
            st.stuck, st.acks, st.maxgap);
    memset(&st, 0, sizeof st);
    st_t0 = t;
}

static int ir_stream(int nframes) {
    const int N = max_frag + 1;
    uint8_t *img = calloc(1, (size_t)N * 300);
    uint8_t ack[OUT_LEN], r[IN_LEN];
    char cmd[256];
    int cmd_len = 0;
    mcu_req(ack, 0x03, 0x00);
#define SEND_ACK(missed, mfrag, afrag)              \
    do {                                            \
        ack[12] = (uint8_t)(missed);                \
        ack[13] = (uint8_t)(mfrag);                 \
        ack[14] = (uint8_t)(afrag);                 \
        ack[47] = crc8(ack + 11, 36);               \
        send_out(ack);                              \
        st.acks++;                                  \
    } while (0)
    SEND_ACK(0, 0, 0);
    int prev = 0, end_sent = 1, missed_no = 0, missed = 0, started = 0, frames = 0, motion = 0;
    uint8_t buttons[3] = {0};
    long long last_data = now_ms(), frame_t0 = now_ms(), last_progress = now_ms(), last_nudge = 0;
    // A frame takes ~15 ms per fragment; give up (the app restarts us) if none completes in time.
    const int stall_ms = N * 15 * 3 + 3000;
    if (stream_out) {
        uint8_t hd[16] = {'J', 'R', (uint8_t)res, (uint8_t)(res >> 8), (uint8_t)max_frag};
        emit_packet(hd, NULL, 0);
    }
    st_t0 = lk_t0 = now_ms();
    struct pollfd pf[2] = {{fd, POLLIN, 0}, {0, POLLIN, 0}};
    while (!stop_req && (nframes <= 0 || frames < nframes)) {
        int np = stream_out ? 2 : 1;
        pf[0].revents = pf[1].revents = 0;
        int pr = poll(pf, np, 100);
        if (pr < 0 && errno != EINTR) break;
        if (np == 2 && (pf[1].revents & (POLLIN | POLLHUP))) {
            int n = read(0, cmd + cmd_len, sizeof cmd - 1 - cmd_len);
            if (n <= 0) { fprintf(stderr, "stdin closed (%d, revents %x)\n", n, pf[1].revents); stop_req = 1; break; }  // app closed the pipe
            for (int k = 0; k < n; k++)
                if (!cmd[cmd_len + k]) cmd[cmd_len + k] = ' ';
            cmd_len += n;
            cmd[cmd_len] = 0;
            char *nl;
            while ((nl = strchr(cmd, '\n'))) {
                *nl = 0;
                handle_cmd(cmd);
                cmd_len -= (int)(nl + 1 - cmd);
                memmove(cmd, nl + 1, (size_t)cmd_len + 1);
            }
            if (cmd_len >= (int)sizeof cmd - 1) cmd_len = 0;
        }
        long long t = now_ms();
        stats_tick(t, 0);
        wifi_ps_hold_off(t);
        if (stream_out) link_tick(t);
        if (t - last_data > 4000 || t - frame_t0 > stall_ms) {
            fprintf(stderr, "stream stalled (%lld ms since data, %lld ms in frame, last frag %d)\n", t - last_data, t - frame_t0, prev);
            stats_tick(t, 1);
            free(img);
            return -1;
        }
        if (!lossy && t - last_progress > 500 && t - last_nudge > 300) {  // stuck on a fragment: ask to resend
            last_nudge = t;
            st.stuck++;
            SEND_ACK(1, (prev + 1) % N, 0);
        }
        if (pr == 0) {  // 100 ms without reports: nudge
            st.nudges++;
            SEND_ACK(0, 0, prev);
            continue;
        }
        if (!(pf[0].revents & POLLIN)) continue;
        int n = recv_in(r, 0);
        if (n < 0) break;
        if (verbose > 3) fprintf(stderr, "%8lld RX %02x len=%d mcu=%02x frag=%d\n", now_us(), r[0], n, n > 52 ? r[49] : 0, n > 52 ? r[52] : -1);
        if (n < 59 || r[0] != 0x31) continue;
        {
            long long tr = now_ms();
            if (st_last_rx && tr - st_last_rx > st.maxgap) st.maxgap = (int)(tr - st_last_rx);
            if (st_last_rx && tr - st_last_rx > lk.maxgap) lk.maxgap = (int)(tr - st_last_rx);
            lk.reports++;
            st_last_rx = tr;
            st.reports++;
        }
        int m = imu_motion(r);
        if (m > motion) motion = m;
        if (stream_out && memcmp(buttons, r + 3, 3)) {
            memcpy(buttons, r + 3, 3);
            uint8_t hd[16] = {'J', 'B', r[3], r[4], r[5]};
            emit_packet(hd, NULL, 0);
        }
        if (r[49] == 0x03 && n >= 359) {
            int f = r[52], store = 0;
            last_data = now_ms();
            if (lossy) {
                // Every fragment carries its own position: keep it and move on. A lost one just shows
                // the previous frame's data until the next pass; resend requests stall the stream.
                if (started && f == prev) {
                    st.repeats++;
                } else {
                    if (started && f != (prev + 1) % N) { st.skips++; lk.skips++; }
                    started = 1;
                    store = 1;
                }
                SEND_ACK(0, 0, f);
            } else if (f == (prev + 1) % N || (!started && f == 0)) {
                started = 1;
                SEND_ACK(0, 0, f);
                store = 1;
            } else if (f || prev) {
                if (f == prev) {  // repeat
                    st.repeats++;
                    SEND_ACK(0, 0, f);
                    missed = 0;
                } else if (missed_no != f && !missed) {  // skipped some: ask to resend from prev+1
                    st.skips++;
                    if (max_frag != 0x03) {
                        SEND_ACK(1, (prev + 1) % N, 0);
                        missed_no = f - 1;
                        missed = 1;
                    } else {
                        SEND_ACK(0, 0, f);
                    }
                    store = 1;
                } else if (missed_no == f) {  // got the missed one
                    st.recovered++;
                    SEND_ACK(0, 0, f);
                    missed = 0;
                    store = 1;
                } else {  // straggler
                    st.stragglers++;
                    SEND_ACK(0, 0, prev);
                }
            }
            if (store) {
                // Frame end: the last fragment, or (lossy) a wrap-around that skipped it.
                int end = f == max_frag || (lossy && f <= prev && !end_sent);
                end_sent = f == max_frag;
                prev = f;
                lk.frags++;
                st.frags++;
                last_progress = last_data;
                memcpy(img + 300 * f, r + 59, 300);
                if (stream_out) {
                    int white = r[55] | r[56] << 8, noise = r[57] | r[58] << 8, mo = motion > 0xffff ? 0xffff : motion;
                    uint8_t hd[16] = {'J', 'F', (uint8_t)f, (uint8_t)max_frag, r[53], (uint8_t)end,
                                      (uint8_t)white, (uint8_t)(white >> 8), (uint8_t)noise, (uint8_t)(noise >> 8),
                                      (uint8_t)exposure_us, (uint8_t)(exposure_us >> 8), (uint8_t)mo, (uint8_t)(mo >> 8),
                                      (uint8_t)gain, 0};
                    emit_packet(hd, r + 59, 300);
                    motion = 0;
                }
                if (end) {
                    emit_frame(img, r, (int)(last_data - frame_t0));
                    frame_t0 = last_data;
                    frames++;
                    if (autoexp) auto_exposure(r);
                }
            }
        } else if (r[49] == 0xff) {  // empty MCU report: ack again, otherwise it drops to 30 ms/fragment
            st.empty++;
            SEND_ACK(0, 0, prev);
        } else if (r[49] == 0x00) {
            if (lossy) SEND_ACK(0, 0, prev);
            else SEND_ACK(1, (prev + 1) % N, 0);
        }
    }
    free(img);
    return 0;
}

static char *find_right_joycon(void) {
    static char path[64];
    DIR *d = opendir("/sys/class/hidraw");
    struct dirent *e;
    if (!d) return NULL;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "hidraw", 6)) continue;
        char p[256], buf[512];
        snprintf(p, sizeof p, "/sys/class/hidraw/%s/device/uevent", e->d_name);
        FILE *f = fopen(p, "r");
        if (!f) continue;
        size_t n = fread(buf, 1, sizeof buf - 1, f);
        fclose(f);
        buf[n] = 0;
        if (strstr(buf, "0000057E:00002007")) {
            snprintf(path, sizeof path, "/dev/%s", e->d_name);
            closedir(d);
            return path;
        }
    }
    closedir(d);
    return NULL;
}

static volatile sig_atomic_t last_sig;
static void on_sig(int s) { last_sig = s; stop_req = 1; }

int main(int argc, char **argv) {
    const char *dev = NULL;
    int nframes = 1, reset_only = 0, opt;
    while ((opt = getopt(argc, argv, "d:r:e:g:l:i:fan:o:xvm:w:")) != -1) {
        switch (opt) {
            case 'd': dev = optarg; break;
            case 'r': res = atoi(optarg); break;
            case 'e': exposure_us = atoi(optarg); break;
            case 'g': gain = atoi(optarg); break;
            case 'l': leds = (int)strtol(optarg, NULL, 0); break;
            case 'i': intensity = atoi(optarg); break;
            case 'f': exfilter = 1; break;
            case 'a': autoexp = 1; break;
            case 'n': nframes = atoi(optarg); break;
            case 'o': if (!strcmp(optarg, "-")) stream_out = 1; else out_path = optarg; break;
            case 'x': reset_only = 1; break;
            case 'v': verbose++; break;
            case 'w': wifi_tweak = atoi(optarg); break;
            case 'm': lossy = atoi(optarg); break;
            default: fprintf(stderr, "bad option\n"); return 2;
        }
    }
    switch (res) {
        case 160: res_reg = 0x50; max_frag = 0x3f; break;  // binning 2x2
        case 80: res_reg = 0x64; max_frag = 0x0f; break;   // binning 4x2, skipping 1x2
        case 40: res_reg = 0x69; max_frag = 0x03; break;   // binning 4x2, skipping 2x4
        default: res = 320; res_reg = 0x00; max_frag = 0xff; break;
    }
    if (!dev) dev = find_right_joycon();
    if (!dev) {
        fprintf(stderr, "Right Joy-Con not found (is it connected?)\n");
        return 10;
    }
    fd = open(dev, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", dev, strerror(errno));
        return 11;
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_sig;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    if (reset_only) {
        ir_stop();
        return 0;
    }
    if (verbose) fprintf(stderr, "%s: %dx%d, exposure %d us\n", dev, res, res * 3 / 4, exposure_us);
    if (wifi_tweak && (wifi_ps_was_on = wifi_ps_on())) {
        if (verbose) fprintf(stderr, "Wi-Fi power save off while streaming\n");
        wifi_ps_set(0);
    }
    long long t0 = now_ms();
    int rc = ir_start();
    if (rc >= 3 && !stop_req) {  // MCU left in IR mode by a killed session: power-cycle it once
        ir_stop();
        rc = ir_start();
    }
    if (!rc) {
        if (verbose) fprintf(stderr, "camera ready in %lld ms\n", now_ms() - t0);
        if (ir_stream(nframes)) rc = 20;
    }
    if (verbose) fprintf(stderr, "stopping: rc %d, signal %d\n", rc, (int)last_sig);
    stop_req = 0;
    ir_stop();
    close(fd);
    if (wifi_ps_was_on) wifi_ps_set(1);
    return rc;
}
