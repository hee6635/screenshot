package com.hee.tempshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Pure row-matching math for scroll capture (no Android types, so it is unit-tested). */
final class Stitcher {
    static final int MIN_VOTES = 5;
    static final int SEGMENTS = 4; // columns per row for matchRatio; one may be covered by a floating button

    /**
     * One hash per row (every 2nd pixel); info[y] is false for single-color rows, which match anything.
     * Skips the outer edges: scroll bars fade in at the right while scrolling and would make every row
     * they touch look different.
     */
    static void rowHashes(int[] px, int w, int h, int[] hash, boolean[] info) {
        int x0 = w * 3 / 100, x1 = w * 94 / 100;
        for (int y = 0; y < h; y++) {
            int base = y * w, first = px[base + x0], hh = 17;
            boolean varied = false;
            for (int x = x0; x < x1; x += 2) {
                int p = px[base + x];
                hh = hh * 31 + p;
                if (p != first) varied = true;
            }
            hash[y] = hh;
            info[y] = varied;
        }
    }

    /** SEGMENTS hashes per row (seg[y * SEGMENTS + k]) over the same columns rowHashes uses. */
    static void segmentHashes(int[] px, int w, int h, int[] seg) {
        int x0 = w * 3 / 100, x1 = w * 94 / 100, span = Math.max(1, (x1 - x0) / SEGMENTS);
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int k = 0; k < SEGMENTS; k++) {
                int hh = 17, from = x0 + k * span, to = k == SEGMENTS - 1 ? x1 : from + span;
                for (int x = from; x < to; x += 2) hh = hh * 31 + px[base + x];
                seg[y * SEGMENTS + k] = hh;
            }
        }
    }

    /** Rows identical at the top of both frames (status bar, sticky header). */
    static int sameTop(int[] a, int[] b) {
        int i = 0;
        while (i < a.length && a[i] == b[i]) i++;
        return i;
    }

    /** Row y of two frames counts as the same if all but one of its segments match (see segmentHashes). */
    static boolean sameRow(int[] segA, int[] segB, int y) {
        int same = 0;
        for (int k = 0; k < SEGMENTS; k++) if (segA[y * SEGMENTS + k] == segB[y * SEGMENTS + k]) same++;
        return same >= SEGMENTS - 1;
    }

    /** Like sameTop, but a corner overlay (screen-recorder timer, floating button) doesn't end the header. */
    static int sameTopRows(int[] segA, int[] segB, int h) {
        int i = 0;
        while (i < h && sameRow(segA, segB, i)) i++;
        return i;
    }

    /** Like sameBottom, tolerant of a corner overlay (date chip, floating button). */
    static int sameBottomRows(int[] segA, int[] segB, int h, int limit) {
        int i = 0;
        while (i < limit && sameRow(segA, segB, h - 1 - i)) i++;
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
     * prefers [minD, maxD] (the swipe distance) when it fully lines up, so repeating list items don't
     * pick a wrong period; otherwise the shift with the most matching rows wins.
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
        // informative rows in [top, top + i), to know how many rows could match for a given shift
        int[] infoBefore = new int[bottom - top + 1];
        for (int y = top; y < bottom; y++) infoBefore[y - top + 1] = infoBefore[y - top] + (infoB[y] ? 1 : 0);
        int window = best(votes, Math.max(1, minD), Math.min(votes.length - 1, maxD));
        // Trust the swipe window only if (nearly) every overlapping row matches there: true for the real
        // shift and for repeating list items, not for a few chance matches when the list barely moved
        // (end of the page). Otherwise take the shift with the most matching rows.
        if (window != -1 && votes[window] * 10 >= infoBefore[bottom - top - window] * 9) return window;
        return best(votes, 1, votes.length - 1);
    }

    /**
     * Share of informative rows that line up for shift d, comparing segment hashes (see segmentHashes).
     * A row counts if all but one of its segments match, so a floating button pinned in one corner
     * (KakaoTalk's home button, a FAB) doesn't fail every row it covers. Near 1 for a real scroll; low
     * when the content changed underneath (a chat still typing, an animation).
     */
    static float matchRatio(int[] segA, int[] segB, boolean[] infoB, int top, int bottom, int d) {
        int match = 0, total = 0;
        for (int y = top; y + d < bottom; y++) {
            if (!infoB[y]) continue;
            total++;
            int same = 0;
            for (int k = 0; k < SEGMENTS; k++) {
                if (segB[y * SEGMENTS + k] == segA[(y + d) * SEGMENTS + k]) same++;
            }
            if (same >= SEGMENTS - 1) match++;
        }
        return total == 0 ? 0f : (float) match / total;
    }

    /**
     * Rows [y0, y1) of the newest frame to append. taken = page rows already in the result,
     * offset = page row at this frame's y=0, cut = lowest frame row used (kept above floating
     * buttons, toasts and input bars), regionTop = first row that scrolls.
     */
    static int[] strip(int taken, int offset, int cut, int regionTop) {
        // If one swipe moved further than cut - regionTop, the rows in between were never on a
        // trusted part of the screen; start at regionTop and accept the small gap.
        int y0 = Math.max(taken - offset, regionTop);
        return new int[]{y0, Math.max(y0, cut)};
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
