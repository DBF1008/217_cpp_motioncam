package com.motioncam.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.motioncam.camera.CameraManualControl;

import org.junit.Test;

/**
 * Regression tests for aperture-metadata fallback in {@link PostProcessViewModel}.
 *
 * Previously {@code update()} dereferenced {@code cameraApertures[0]} exactly when the
 * array was {@code null} or empty, which crashed the very first post-process estimate
 * on devices that do not report aperture metadata (taking down the whole preview/save
 * flow). These tests pin the corrected fallback so the regression cannot return.
 *
 * They exercise only the pure {@link PostProcessViewModel#resolveAperture(float[])}
 * decision and the dependency-free {@link CameraManualControl}, so they run on the
 * local JVM without any Android runtime.
 */
public class PostProcessViewModelTest {
    private static final float DELTA = 1e-6f;

    @Test
    public void usesReportedApertureWhenAvailable() {
        assertEquals(2.8f, PostProcessViewModel.resolveAperture(new float[] { 2.8f, 4.0f }), DELTA);
    }

    @Test
    public void fallsBackToDefaultWhenNull() {
        assertEquals(PostProcessViewModel.DEFAULT_APERTURE,
                PostProcessViewModel.resolveAperture(null), DELTA);
    }

    @Test
    public void fallsBackToDefaultWhenEmpty() {
        assertEquals(PostProcessViewModel.DEFAULT_APERTURE,
                PostProcessViewModel.resolveAperture(new float[0]), DELTA);
    }

    @Test
    public void fallsBackToDefaultWhenNonPositive() {
        assertEquals(PostProcessViewModel.DEFAULT_APERTURE,
                PostProcessViewModel.resolveAperture(new float[] { 0.0f }), DELTA);
        assertEquals(PostProcessViewModel.DEFAULT_APERTURE,
                PostProcessViewModel.resolveAperture(new float[] { -2.0f }), DELTA);
    }

    @Test
    public void resolvedApertureKeepsEvFiniteWhenMetadataMissing() {
        // The point of the fallback: EV estimation must stay finite even when no
        // aperture metadata is present. With a zero/invalid aperture, getEv() would
        // compute log2(0) == -Infinity and poison the denoise settings.
        float aperture = PostProcessViewModel.resolveAperture(null);

        double ev = CameraManualControl.Exposure.Create(
                CameraManualControl.SHUTTER_SPEED.EXPOSURE_1_100,
                CameraManualControl.ISO.ISO_100).getEv(aperture);

        assertTrue("EV must be finite", !Double.isNaN(ev) && !Double.isInfinite(ev));
    }
}
