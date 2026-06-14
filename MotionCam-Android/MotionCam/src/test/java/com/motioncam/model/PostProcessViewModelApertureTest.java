package com.motioncam.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression tests for aperture metadata handling in PostProcessViewModel.
 *
 * Verifies the fix for the crash that occurred when cameraApertures metadata
 * was null or empty: the original code had an inverted null-check that accessed
 * cameraApertures[0] precisely when the array was null/empty, causing
 * NullPointerException or ArrayIndexOutOfBoundsException.
 */
public class PostProcessViewModelApertureTest {

    private static final float DEFAULT_APERTURE = 1.6f;
    private static final float DELTA = 0.0001f;

    @Test
    public void resolveAperture_nullArray_returnsDefault() {
        float result = PostProcessViewModel.resolveAperture(null);
        assertEquals("null aperture array should fall back to default", DEFAULT_APERTURE, result, DELTA);
    }

    @Test
    public void resolveAperture_emptyArray_returnsDefault() {
        float result = PostProcessViewModel.resolveAperture(new float[]{});
        assertEquals("empty aperture array should fall back to default", DEFAULT_APERTURE, result, DELTA);
    }

    @Test
    public void resolveAperture_validArray_returnsFirstElement() {
        float expected = 2.8f;
        float result = PostProcessViewModel.resolveAperture(new float[]{expected});
        assertEquals("single-element array should return that element", expected, result, DELTA);
    }

    @Test
    public void resolveAperture_multipleElements_returnsFirstElement() {
        float expected = 1.4f;
        float result = PostProcessViewModel.resolveAperture(new float[]{expected, 2.0f, 4.0f});
        assertEquals("multi-element array should return the first element", expected, result, DELTA);
    }

    @Test
    public void resolveAperture_smallAperture_returnsValue() {
        // Some devices report small aperture values like f/1.0
        float expected = 1.0f;
        float result = PostProcessViewModel.resolveAperture(new float[]{expected});
        assertEquals(expected, result, DELTA);
    }

    @Test
    public void resolveAperture_largeAperture_returnsValue() {
        // Some devices report large aperture values like f/22
        float expected = 22.0f;
        float result = PostProcessViewModel.resolveAperture(new float[]{expected});
        assertEquals(expected, result, DELTA);
    }

    @Test
    public void resolveAperture_noExceptionThrown() {
        // Verify that none of the edge cases throw exceptions
        // This is the core regression: the old code would crash on null/empty
        try {
            PostProcessViewModel.resolveAperture(null);
            PostProcessViewModel.resolveAperture(new float[]{});
            PostProcessViewModel.resolveAperture(new float[]{1.8f});
        } catch (Exception e) {
            throw new AssertionError("resolveAperture should never throw, but got: " + e);
        }
    }
}
