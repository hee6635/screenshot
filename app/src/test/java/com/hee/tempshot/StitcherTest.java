package com.hee.tempshot;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

public class StitcherTest {
    private static final int H = 200, HEADER = 10, FOOTER = 12;

    /** Screen = fixed header + page rows starting at offset + fixed footer. */
    private static int[] frame(int[] page, int offset) {
        int[] f = new int[H];
        for (int y = 0; y < H; y++) {
            if (y < HEADER) f[y] = -1000 - y;
            else if (y >= H - FOOTER) f[y] = -2000 - y;
            else f[y] = page[offset + y - HEADER];
        }
        return f;
    }

    private static boolean[] allInfo() {
        boolean[] info = new boolean[H];
        Arrays.fill(info, true);
        return info;
    }

    @Test
    public void findsShiftBetweenScrolledFrames() {
        int[] page = new Random(1).ints(1000).toArray();
        int[] a = frame(page, 0), b = frame(page, 37);
        assertEquals(HEADER, Stitcher.sameTop(a, b));
        assertEquals(FOOTER, Stitcher.sameBottom(a, b, H - HEADER));
        assertEquals(37, Stitcher.findShift(a, b, allInfo(), HEADER, H - FOOTER, 20, 60));
    }

    @Test
    public void unchangedFrameMeansEndOfPage() {
        int[] page = new Random(2).ints(1000).toArray();
        int[] a = frame(page, 50);
        assertEquals(H, Stitcher.sameTop(a, a.clone()));
    }

    @Test
    public void repeatingListPrefersSwipeDistance() {
        int[] page = new int[1000];
        for (int i = 0; i < page.length; i++) page[i] = i % 25; // identical list items every 25 rows
        int[] a = frame(page, 0), b = frame(page, 80);
        // 80 = 5 + 3*25: every multiple-of-25 alias also matches; the swipe window picks the real one.
        assertEquals(80, Stitcher.findShift(a, b, allInfo(), HEADER, H - FOOTER, 70, 100));
    }

    @Test
    public void blankRowsDontVote() {
        int[] page = new int[1000]; // all one color
        int[] a = frame(page, 0), b = frame(page, 30);
        boolean[] none = new boolean[H];
        assertEquals(-1, Stitcher.findShift(a, b, none, HEADER, H - FOOTER, 10, 50));
    }

    @Test
    public void stripsComeFromAboveTheCutAndChainWithoutOverlap() {
        int regionTop = 100, cut = 700;
        int taken = cut; // first frame contributed rows [0, 700)
        int[] s1 = Stitcher.strip(taken, 400, cut, regionTop); // scrolled 400
        assertEquals(300, s1[0]);
        assertEquals(700, s1[1]);
        taken = 400 + cut;
        int[] s2 = Stitcher.strip(taken, 750, cut, regionTop); // scrolled 350 more
        assertEquals(350, s2[0]);
        assertEquals(700, s2[1]);
    }

    @Test
    public void tooLongSwipeStartsAtRegionTop() {
        int[] s = Stitcher.strip(700, 700, 700, 100); // moved a whole cut at once
        assertEquals(100, s[0]);
        assertEquals(700, s[1]);
    }

    @Test
    public void shortMoveAtEndOfPageBeatsSwipeWindow() {
        // Hit the bottom: moved only 12 rows although the swipe expected ~80.
        // A few repeated rows also line up at 80 by chance; the real shift must still win.
        int[] page = new Random(3).ints(1000).toArray();
        for (int i = 0; i < 8; i++) page[100 + i] = page[20 + i]; // chance match 80 rows apart
        int[] a = frame(page, 0), b = frame(page, 12);
        assertEquals(12, Stitcher.findShift(a, b, allInfo(), HEADER, H - FOOTER, 60, 110));
    }

    /** Segment hashes for a synthetic frame: each row's segments derive from its row value. */
    private static int[] segs(int[] frame) {
        int[] seg = new int[frame.length * Stitcher.SEGMENTS];
        for (int y = 0; y < frame.length; y++)
            for (int k = 0; k < Stitcher.SEGMENTS; k++) seg[y * Stitcher.SEGMENTS + k] = frame[y] * 7 + k;
        return seg;
    }

    @Test
    public void matchRatioSeparatesRealScrollFromChangedContent() {
        int[] page = new Random(4).ints(1000).toArray();
        int[] a = frame(page, 0), b = frame(page, 40);
        assertEquals(1f, Stitcher.matchRatio(segs(a), segs(b), allInfo(), HEADER, H - FOOTER, 40), 0.001f);
        // Content changed underneath (e.g. new chat text pushed things around): only a few rows still fit.
        int[] changed = b.clone();
        for (int y = HEADER + 20; y < H - FOOTER; y++) changed[y] = 7_000_000 + y;
        org.junit.Assert.assertTrue(Stitcher.matchRatio(segs(a), segs(changed), allInfo(), HEADER, H - FOOTER, 40) < 0.9f);
    }

    @Test
    public void floatingButtonInOneCornerDoesNotFailTheSeam() {
        int[] page = new Random(6).ints(1000).toArray();
        int[] a = frame(page, 0), b = frame(page, 40);
        int[] sa = segs(a), sb = segs(b);
        // A fixed home button covers the right segment of 30 rows in both frames (it doesn't scroll).
        for (int y = HEADER; y < HEADER + 30; y++) {
            sa[y * Stitcher.SEGMENTS + 3] = -42;
            sb[y * Stitcher.SEGMENTS + 3] = -42;
        }
        org.junit.Assert.assertTrue(Stitcher.matchRatio(sa, sb, allInfo(), HEADER, H - FOOTER, 40) >= 0.9f);
    }

    @Test
    public void rowHashesIgnoreScrollBarAtTheRightEdge() {
        int w = 100;
        int[] row = new java.util.Random(5).ints(w).toArray();
        int[] withBar = row.clone();
        for (int x = 96; x < 100; x++) withBar[x] = 0xFF888888; // scroll bar thumb
        int[] h1 = new int[1], h2 = new int[1];
        Stitcher.rowHashes(row, w, 1, h1, new boolean[1]);
        Stitcher.rowHashes(withBar, w, 1, h2, new boolean[1]);
        assertEquals(h1[0], h2[0]);
    }

    @Test
    public void headerWithChangingTimerInOneCornerStillCountsAsFixed() {
        int[] page = new Random(7).ints(1000).toArray();
        int[] sa = segs(frame(page, 0)), sb = segs(frame(page, 40));
        // Screen-recorder timer in the header's right segment changes between the two shots.
        sb[3 * Stitcher.SEGMENTS + 3] = 12345;
        assertEquals(HEADER, Stitcher.sameTopRows(sa, sb, H));
        assertEquals(FOOTER, Stitcher.sameBottomRows(sa, sb, H, H - HEADER));
    }

    // --- tolerant matching ---

    /** Synthetic page with text-like rows; jitter adds small brightness noise like fractional-pixel anti-aliasing. */
    private static int[] screen(int w, int h, int offset, int jitter, long seed) {
        Random rnd = new Random(seed);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int py = y + offset;
            Random row = new Random(py * 7919L);
            boolean text = (py / 6) % 3 != 2; // two text lines, then a blank gap
            for (int x = 0; x < w; x++) {
                int l = 240;
                if (y < 20) l = 60; // fixed header
                else if (text) l = row.nextInt(8) < 3 ? 30 : 235;
                if (jitter > 0) l = Math.max(0, Math.min(255, l + rnd.nextInt(2 * jitter + 1) - jitter));
                px[y * w + x] = 0xFF000000 | l << 16 | l << 8 | l;
            }
        }
        return px;
    }

    @Test
    public void tolerantShiftSurvivesAntiAliasingNoise() {
        int w = 200, h = 400;
        int[] a = screen(w, h, 0, 0, 1), b = screen(w, h, 130, 6, 2); // scrolled 130, slightly different pixels
        int[] ha = new int[h], hb = new int[h];
        boolean[] ia = new boolean[h], ib = new boolean[h];
        Stitcher.rowHashes(a, w, h, ha, ia);
        Stitcher.rowHashes(b, w, h, hb, ib);
        // exact hashes can't line anything up any more
        assertEquals(-1, Stitcher.findShift(ha, hb, ib, 20, 380, 60, 200));
        byte[] sa = new byte[h * Stitcher.SIG], sb = new byte[h * Stitcher.SIG];
        boolean[] sia = new boolean[h], sib = new boolean[h];
        Stitcher.rowSigs(a, w, h, sa, sia);
        Stitcher.rowSigs(b, w, h, sb, sib);
        int[] m = Stitcher.tolShift(sa, sb, sib, 20, 380, 60, 200);
        assertEquals(130, m[0]);
        org.junit.Assert.assertTrue(m[1] >= Stitcher.TOL_MATCH * 1000);
        // and the unmoved pair would score far lower than the real shift
        org.junit.Assert.assertTrue(Stitcher.tolRatio(sa, sb, sib, 20, 380, 0, 1) * 1000 < m[1]);
    }

    @Test
    public void tolerantShiftFindsMoveOutsideTheWindow() {
        int w = 200, h = 400;
        int[] a = screen(w, h, 0, 3, 3), b = screen(w, h, 17, 3, 4); // end of page: only 17 rows
        byte[] sa = new byte[h * Stitcher.SIG], sb = new byte[h * Stitcher.SIG];
        boolean[] sia = new boolean[h], sib = new boolean[h];
        Stitcher.rowSigs(a, w, h, sa, sia);
        Stitcher.rowSigs(b, w, h, sb, sib);
        int[] all = Stitcher.tolShift(sa, sb, sib, 20, 380, 1, 360);
        assertEquals(17, all[0]);
    }
}
