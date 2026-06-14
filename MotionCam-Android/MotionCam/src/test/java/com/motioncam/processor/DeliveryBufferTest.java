package com.motioncam.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Regression tests for {@link DeliveryBuffer}, the mechanism that keeps processing results from
 * being lost when the consumer (an Activity/Fragment) detaches while it is paused.
 */
public class DeliveryBufferTest {

    /** Pure-JVM stand-in for {@code ProcessorReceiver.Receiver} that records the calls it gets. */
    private static class RecordingReceiver {
        final List<String> events = new ArrayList<>();

        void onProcessingStarted() {
            events.add("started");
        }

        void onProcessingProgress(int progress) {
            events.add("progress:" + progress);
        }

        void onPreviewSaved(String path) {
            events.add("preview:" + path);
        }

        void onProcessingCompleted(String path) {
            events.add("completed:" + path);
        }
    }

    @Test
    public void deliversImmediatelyWhenConsumerAttached() {
        DeliveryBuffer<RecordingReceiver> buffer = new DeliveryBuffer<>();
        RecordingReceiver receiver = new RecordingReceiver();

        buffer.setConsumer(receiver);

        buffer.post(RecordingReceiver::onProcessingStarted);
        buffer.post(r -> r.onProcessingCompleted("img.jpg"));

        assertEquals(Arrays.asList("started", "completed:img.jpg"), receiver.events);
    }

    /**
     * The regression: results that arrive while the consumer is detached (the Activity/Fragment is
     * paused / backgrounded) must be replayed, in order, once it re-attaches. This is what used to
     * fail - the {@code COMPLETED}/{@code PREVIEW_READY} events were dropped and the thumbnail was
     * left stuck in the "processing" state forever.
     */
    @Test
    public void replaysBufferedResultsInOrderWhenConsumerReattaches() {
        DeliveryBuffer<RecordingReceiver> buffer = new DeliveryBuffer<>();

        // No consumer attached (UI is in the background): nothing can be delivered yet.
        buffer.post(RecordingReceiver::onProcessingStarted);
        buffer.post(r -> r.onPreviewSaved("img.jpg"));
        buffer.post(r -> r.onProcessingProgress(100));
        buffer.post(r -> r.onProcessingCompleted("img.jpg"));

        RecordingReceiver receiver = new RecordingReceiver();
        assertTrue("nothing should be delivered before a consumer attaches", receiver.events.isEmpty());

        // UI returns to the foreground and re-attaches (onResume).
        buffer.setConsumer(receiver);

        assertEquals(
                Arrays.asList("started", "preview:img.jpg", "progress:100", "completed:img.jpg"),
                receiver.events);
    }

    @Test
    public void detachingThenReattachingBuffersTheSecondRound() {
        DeliveryBuffer<RecordingReceiver> buffer = new DeliveryBuffer<>();
        RecordingReceiver first = new RecordingReceiver();

        buffer.setConsumer(first);
        buffer.post(RecordingReceiver::onProcessingStarted);

        // Detach (onPause): subsequent results must be buffered, not sent to the old consumer.
        buffer.setConsumer(null);
        buffer.post(r -> r.onProcessingCompleted("img.jpg"));

        assertEquals(Arrays.asList("started"), first.events);

        // Re-attach (onResume): the buffered completion is replayed to the new consumer.
        RecordingReceiver second = new RecordingReceiver();
        buffer.setConsumer(second);

        assertEquals(Arrays.asList("completed:img.jpg"), second.events);
    }

    @Test
    public void detachingWhileAlreadyDetachedKeepsBufferedResults() {
        DeliveryBuffer<RecordingReceiver> buffer = new DeliveryBuffer<>();
        buffer.post(r -> r.onProcessingCompleted("img.jpg"));

        // Detaching while already detached must be a no-op and must not lose the buffered result.
        buffer.setConsumer(null);

        RecordingReceiver receiver = new RecordingReceiver();
        buffer.setConsumer(receiver);

        assertEquals(Arrays.asList("completed:img.jpg"), receiver.events);
    }
}
