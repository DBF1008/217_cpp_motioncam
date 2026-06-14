package com.motioncam.processor;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Buffers result events that arrive while no UI listener is attached,
 * and replays them in order when a listener re-attaches.
 *
 * <p>This solves the bug where ProcessorService delivers PREVIEW_READY / COMPLETED
 * events while the Activity is paused (receiver == null), causing those events to
 * be silently dropped and thumbnails to be permanently stuck in "processing" state.</p>
 *
 * <p>This class has no Android framework dependencies beyond {@link Bundle},
 * making it straightforward to unit-test with plain JUnit + Robolectric.</p>
 */
class ResultEventBuffer {

    /**
     * Callback for dispatching a result event to the UI layer.
     */
    interface DispatchCallback {
        void onDispatch(int resultCode, Bundle resultData);
    }

    private static class PendingEvent {
        final int resultCode;
        final Bundle resultData;

        PendingEvent(int resultCode, Bundle resultData) {
            this.resultCode = resultCode;
            this.resultData = resultData;
        }
    }

    private final List<PendingEvent> mPendingEvents = new ArrayList<>();
    private boolean mReceiverAttached;
    private DispatchCallback mCallback;

    /**
     * Attaches or detaches the dispatch callback.
     *
     * <p>When a non-null callback is attached, any events that were buffered
     * while no callback was attached are replayed in the order they arrived.
     * When null is passed, subsequent events will be buffered until a callback
     * is attached again.</p>
     *
     * @param callback the callback to dispatch events to, or null to detach
     */
    void setCallback(DispatchCallback callback) {
        mReceiverAttached = (callback != null);
        mCallback = callback;

        if (mReceiverAttached && !mPendingEvents.isEmpty()) {
            List<PendingEvent> eventsToReplay = new ArrayList<>(mPendingEvents);
            mPendingEvents.clear();

            for (PendingEvent event : eventsToReplay) {
                mCallback.onDispatch(event.resultCode, event.resultData);
            }
        }
    }

    /**
     * Delivers an event to the attached callback, or buffers it for later replay.
     *
     * @param resultCode  the result code identifying the event type
     * @param resultData  the event payload
     */
    void onEvent(int resultCode, Bundle resultData) {
        if (mReceiverAttached && mCallback != null) {
            mCallback.onDispatch(resultCode, resultData);
        } else {
            mPendingEvents.add(new PendingEvent(resultCode, resultData));
        }
    }

    /**
     * Returns the number of events currently buffered and awaiting delivery.
     */
    int getPendingEventCount() {
        return mPendingEvents.size();
    }

    /**
     * Discards all buffered events without delivering them.
     */
    void clearPendingEvents() {
        mPendingEvents.clear();
    }
}
