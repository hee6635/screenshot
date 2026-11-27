package com.hee.tempshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CropSnapTest {
    private static float iou(int[] a, int[] b) {
        return CropActivity.CropView.iou(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]);
    }

    @Test
    public void roughlyDrawnBoxOverACardSnaps() {
        int[] card = {100, 300, 900, 700};
        int[] drawn = {130, 330, 880, 720}; // a quick diagonal swipe a bit off the card's edges
        assertTrue(iou(drawn, card) >= 0.6f);
    }

    @Test
    public void boxOverHalfACardDoesNotSnap() {
        int[] card = {100, 300, 900, 700};
        int[] drawn = {100, 300, 500, 700}; // the user wanted only the left half
        assertTrue(iou(drawn, card) < 0.6f);
    }

    @Test
    public void identicalAndDisjoint() {
        int[] a = {0, 0, 10, 10};
        assertEquals(1f, iou(a, a), 1e-6);
        assertEquals(0f, iou(a, new int[]{20, 20, 30, 30}), 1e-6);
    }
}
