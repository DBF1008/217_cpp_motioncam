package com.motioncam.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link ResultEventBuffer}, which backs the event-buffering
 * behaviour of {@link ProcessorReceiver}.
 *
 * <h3>Regression coverage</h3>
 * <p>The core bug was: when the Activity is paused ({@code setReceiver(null)}),
 * {@code ProcessorService} continues to send PREVIEW_READY / COMPLETED events.
 * Previously these events were silently dropped, leaving thumbnails stuck in
 * "processing" state permanently. The fix buffers events while no receiver is
 * attached and replays them when the receiver re-attaches.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class ResultEventBufferTest {

    /**
     * Simple recorder that captures dispatched events for assertion.
     */
    private static class EventRecorder implements ResultEventBuffer.DispatchCallback {
        final List<Integer> resultCodes = new ArrayList<>();
        final List<Bundle> resultDataList = new ArrayList<>();

        @Override
        public void onDispatch(int resultCode, Bundle resultData) {
            resultCodes.add(resultCode);
            resultDataList.add(resultData);
        }
    }

    private ResultEventBuffer buffer;

    @Before
    public void setUp() {
        buffer = new ResultEventBuffer();
    }

    // ── Basic delivery ───────────────────────────────────────────────

    @Test
    public void eventDeliveredImmediatelyWhenCallbackAttached() {
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);

        Bundle data = new Bundle();
        data.putString("key", "value");
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, data);

        assertEquals("Event should be delivered immediately", 1, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_COMPLETED, (int) recorder.resultCodes.get(0));
        assertEquals("value", recorder.resultDataList.get(0).getString("key"));
    }

    @Test
    public void multipleEventsDeliveredInOrderWhenCallbackAttached() {
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);

        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_STARTED, Bundle.EMPTY);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, Bundle.EMPTY);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PROGRESS, Bundle.EMPTY);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, Bundle.EMPTY);

        assertEquals(4, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_STARTED, (int) recorder.resultCodes.get(0));
        assertEquals(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, (int) recorder.resultCodes.get(1));
        assertEquals(ProcessorReceiver.PROCESS_CODE_PROGRESS, (int) recorder.resultCodes.get(2));
        assertEquals(ProcessorReceiver.PROCESS_CODE_COMPLETED, (int) recorder.resultCodes.get(3));
    }

    // ── Buffering when no callback ───────────────────────────────────

    @Test
    public void eventBufferedWhenNoCallbackAttached() {
        // No callback attached — events should be buffered, not lost
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, Bundle.EMPTY);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, Bundle.EMPTY);

        assertEquals("Events should be buffered", 2, buffer.getPendingEventCount());
    }

    @Test
    public void bufferedEventsReplayedWhenCallbackAttaches() {
        // Simulate the bug scenario: events arrive while Activity is paused
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, Bundle.EMPTY);

        Bundle completedData = new Bundle();
        completedData.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/path/to/output.jpg");
        completedData.putString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY, "content://media/123");
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, completedData);

        assertEquals("Events should be buffered while no callback", 2, buffer.getPendingEventCount());

        // Activity resumes — attach callback
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);

        // Buffered events should have been replayed
        assertEquals("All buffered events should be replayed", 2, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, (int) recorder.resultCodes.get(0));
        assertEquals(ProcessorReceiver.PROCESS_CODE_COMPLETED, (int) recorder.resultCodes.get(1));

        // Verify data integrity through the replay
        assertEquals("/path/to/output.jpg",
                recorder.resultDataList.get(1).getString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY));
        assertEquals("content://media/123",
                recorder.resultDataList.get(1).getString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY));

        // Buffer should be empty after replay
        assertEquals("Buffer should be empty after replay", 0, buffer.getPendingEventCount());
    }

    // ── Regression test: the exact bug scenario ─────────────────────

    @Test
    public void regression_pauseResumeCyclePreservesCompletionEvents() {
        // 1. Activity is running with callback attached (normal operation)
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);

        // 2. User takes a photo — processing starts
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_STARTED, Bundle.EMPTY);
        assertEquals(1, recorder.resultCodes.size());

        // 3. User presses Home — Activity.onPause() calls setCallback(null)
        buffer.setCallback(null);

        // 4. Service sends PREVIEW_READY while Activity is paused
        Bundle previewData = new Bundle();
        previewData.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/preview/photo.jpg");
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, previewData);

        // 5. Service sends COMPLETED while Activity is still paused
        Bundle completedData = new Bundle();
        completedData.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/preview/photo.jpg");
        completedData.putString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY, "content://media/456");
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, completedData);

        // Verify: events were buffered, not lost
        assertEquals("Events during pause should be buffered", 2, buffer.getPendingEventCount());
        assertEquals("No new events delivered while paused", 1, recorder.resultCodes.size());

        // 6. User returns — Activity.onResume() re-attaches callback
        buffer.setCallback(recorder);

        // Verify: buffered events were replayed
        assertEquals("All events (1 before pause + 2 during pause) should be delivered",
                3, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_STARTED, (int) recorder.resultCodes.get(0));
        assertEquals(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, (int) recorder.resultCodes.get(1));
        assertEquals(ProcessorReceiver.PROCESS_CODE_COMPLETED, (int) recorder.resultCodes.get(2));

        // 7. New events after resume should also work normally
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PROGRESS, Bundle.EMPTY);
        assertEquals(4, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_PROGRESS, (int) recorder.resultCodes.get(3));
    }

    // ── Detach / re-attach cycles ────────────────────────────────────

    @Test
    public void eventsBufferedAcrossMultipleDetachAttachCycles() {
        EventRecorder recorder = new EventRecorder();

        // Cycle 1: attach, buffer one event, detach
        buffer.setCallback(recorder);
        buffer.setCallback(null);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_STARTED, Bundle.EMPTY);

        // Cycle 2: re-attach (replays), buffer another, detach
        buffer.setCallback(recorder);
        assertEquals("Buffered event should be replayed", 1, recorder.resultCodes.size());

        buffer.setCallback(null);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, Bundle.EMPTY);

        // Cycle 3: re-attach (replays second event)
        buffer.setCallback(recorder);
        assertEquals("Second buffered event should be replayed", 2, recorder.resultCodes.size());
        assertEquals(ProcessorReceiver.PROCESS_CODE_STARTED, (int) recorder.resultCodes.get(0));
        assertEquals(ProcessorReceiver.PROCESS_CODE_COMPLETED, (int) recorder.resultCodes.get(1));
    }

    @Test
    public void settingNullCallbackDoesNotLoseAlreadyBufferedEvents() {
        // Buffer some events
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, Bundle.EMPTY);
        assertEquals(1, buffer.getPendingEventCount());

        // Setting null again should NOT clear the buffer
        buffer.setCallback(null);
        assertEquals("Buffer should survive redundant null-set", 1, buffer.getPendingEventCount());

        // Attach callback — events should still replay
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);
        assertEquals(1, recorder.resultCodes.size());
    }

    // ── Clear ────────────────────────────────────────────────────────

    @Test
    public void clearPendingEventsDiscardsBufferedEvents() {
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_STARTED, Bundle.EMPTY);
        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, Bundle.EMPTY);
        assertEquals(2, buffer.getPendingEventCount());

        buffer.clearPendingEvents();
        assertEquals(0, buffer.getPendingEventCount());

        // Attaching callback after clear should not replay anything
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);
        assertTrue("No events should be delivered after clear", recorder.resultCodes.isEmpty());
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    public void emptyBufferReplayIsNoop() {
        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);
        assertTrue("No events should be delivered from empty buffer", recorder.resultCodes.isEmpty());
    }

    @Test
    public void unknownResultCodesAreBufferedAndReplayed() {
        buffer.onEvent(9999, Bundle.EMPTY);
        assertEquals(1, buffer.getPendingEventCount());

        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);
        assertEquals(1, recorder.resultCodes.size());
        assertEquals(9999, (int) recorder.resultCodes.get(0));
    }

    @Test
    public void bundleDataPreservedThroughBuffering() {
        Bundle data = new Bundle();
        data.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/some/path.jpg");
        data.putString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY, "content://media/789");
        data.putInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY, 42);

        buffer.onEvent(ProcessorReceiver.PROCESS_CODE_COMPLETED, data);

        EventRecorder recorder = new EventRecorder();
        buffer.setCallback(recorder);

        assertEquals(1, recorder.resultCodes.size());
        Bundle received = recorder.resultDataList.get(0);
        assertEquals("/some/path.jpg", received.getString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY));
        assertEquals("content://media/789", received.getString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY));
        assertEquals(42, received.getInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY));
    }
}
