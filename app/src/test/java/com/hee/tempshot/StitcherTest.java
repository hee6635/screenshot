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
}
