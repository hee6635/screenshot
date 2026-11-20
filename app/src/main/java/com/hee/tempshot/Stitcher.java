package com.hee.tempshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Pure row-matching math for scroll capture (no Android types, so it is unit-tested). */
final class Stitcher {
    static final int MIN_VOTES = 5;

    /** One hash per row (every 2nd pixel); info[y] is false for single-color rows, which match anything. */
    static void rowHashes(int[] px, int w, int h, int[] hash, boolean[] info) {
        for (int y = 0; y < h; y++) {
            int base = y * w, first = px[base], hh = 17;
            boolean varied = false;
            for (int x = 0; x < w; x += 2) {
                int p = px[base + x];
                hh = hh * 31 + p;
                if (p != first) varied = true;
            }
            hash[y] = hh;
            info[y] = varied;
        }
    }

    /** Rows identical at the top of both frames (status bar, sticky header). */
    static int sameTop(int[] a, int[] b) {
        int i = 0;
        while (i < a.length && a[i] == b[i]) i++;
        return i;
    }

    /** Rows identical at the bottom of both frames (navigation bar, sticky footer). */
    static int sameBottom(int[] a, int[] b, int limit) {
        int i = 0, n = a.length;
        while (i < limit && a[n - 1 - i] == b[n - 1 - i]) i++;
        return i;
    }

    /**
     * Finds d where b[y] == a[y + d] inside rows [top, bottom). Votes per candidate shift and
     * prefers [minD, maxD] (the swipe distance) so repeating list items don't pick a wrong period.
     * Returns -1 when nothing lines up.
     */
    static int findShift(int[] a, int[] b, boolean[] infoB, int top, int bottom, int minD, int maxD) {
        HashMap<Integer, List<Integer>> where = new HashMap<>();
        for (int y = top; y < bottom; y++) where.computeIfAbsent(a[y], k -> new ArrayList<>()).add(y);
        int[] votes = new int[Math.max(1, bottom - top)];
        for (int y = top; y < bottom; y++) {
            if (!infoB[y]) continue;
            List<Integer> ys = where.get(b[y]);
            if (ys == null) continue;
            for (int ya : ys) {
                int d = ya - y;
                if (d > 0 && d < votes.length) votes[d]++;
            }
        }
        int best = best(votes, Math.max(1, minD), Math.min(votes.length - 1, maxD));
        return best != -1 ? best : best(votes, 1, votes.length - 1);
    }

    private static int best(int[] votes, int from, int to) {
        int best = -1, bestVotes = MIN_VOTES - 1;
        for (int d = from; d <= to; d++) {
            if (votes[d] > bestVotes) {
                bestVotes = votes[d];
                best = d;
            }
        }
        return best;
    }
}
