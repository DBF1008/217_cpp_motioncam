package com.motioncam.processor;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Buffers callbacks for a consumer that may temporarily detach (for example while an
 * Activity/Fragment is paused) and replays them, in order, once a consumer re-attaches.
 *
 * <p>This exists so that processing results delivered while no consumer is listening are not
 * lost: previously such results were dropped, leaving capture thumbnails stuck in the
 * "processing" state forever when processing finished while the UI was in the background.
 *
 * <p>This type is deliberately free of any Android dependency so the buffering/replay behaviour
 * can be unit tested on a plain JVM.
 *
 * @param <C> the consumer that deliveries are dispatched to.
 */
class DeliveryBuffer<C> {
    /**
     * A single deferred dispatch to a consumer.
     */
    interface Delivery<T> {
        void deliverTo(T consumer);
    }

    private C mConsumer;
    private final Queue<Delivery<C>> mPending = new ArrayDeque<>();

    /**
     * Attach or detach the consumer. Passing a non-null consumer immediately replays, in the
     * order they were posted, every delivery that arrived while detached. Passing null detaches
     * so that subsequent deliveries are buffered until a consumer attaches again.
     */
    void setConsumer(C consumer) {
        mConsumer = consumer;

        if (consumer != null) {
            // Drain pending deliveries one at a time so that, if a delivery re-detaches the
            // consumer, the remaining deliveries stay buffered rather than being lost.
            while (mConsumer != null) {
                Delivery<C> delivery = mPending.poll();
                if (delivery == null) {
                    break;
                }

                delivery.deliverTo(mConsumer);
            }
        }
    }

    /**
     * Dispatch a delivery to the attached consumer, or buffer it for later replay if none is
     * currently attached.
     */
    void post(Delivery<C> delivery) {
        if (mConsumer != null) {
            delivery.deliverTo(mConsumer);
        } else {
            mPending.add(delivery);
        }
    }
}
