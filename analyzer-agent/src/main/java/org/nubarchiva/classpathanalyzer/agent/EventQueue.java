package org.nubarchiva.classpathanalyzer.agent;

import org.nubarchiva.classpathanalyzer.common.model.ClassLoadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe buffer to accumulate class loading events.
 * Uses ConcurrentLinkedQueue to avoid blocking application threads.
 * Activates automatic throttling if the queue exceeds 100,000 pending events.
 */
public class EventQueue {

    private static final int THROTTLE_THRESHOLD = 100_000;
    private static final int THROTTLE_SAMPLE_RATE = 10;

    private final ConcurrentLinkedQueue<ClassLoadEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong pendingCount = new AtomicLong(0);
    private final AtomicLong totalCount = new AtomicLong(0);
    private final AtomicBoolean throttling = new AtomicBoolean(false);
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * Adds an event to the queue. If in throttle mode, only accepts 1 out of every 10.
     */
    public void offer(ClassLoadEvent event) {
        long total = totalCount.incrementAndGet();

        if (throttling.get()) {
            if (total % THROTTLE_SAMPLE_RATE != 0) {
                droppedCount.incrementAndGet();
                return;
            }
        }

        long pending = pendingCount.incrementAndGet();
        queue.add(event);

        if (pending > THROTTLE_THRESHOLD && !throttling.getAndSet(true)) {
            System.err.println("[agent] WARNING: queue size exceeded " + THROTTLE_THRESHOLD +
                    " — activating throttle mode (sampling 1/" + THROTTLE_SAMPLE_RATE + ")");
        }
    }

    /**
     * Drains all pending events from the queue into a list.
     * Used by the flush thread.
     */
    public List<ClassLoadEvent> drain() {
        ArrayList<ClassLoadEvent> events = new ArrayList<>();
        ClassLoadEvent event;
        while ((event = queue.poll()) != null) {
            events.add(event);
            pendingCount.decrementAndGet();
        }

        // Deactivate throttle if the queue has been drained
        if (pendingCount.get() < THROTTLE_THRESHOLD / 2) {
            if (throttling.getAndSet(false)) {
                System.err.println("[agent] Throttle deactivated (queue under control)");
            }
        }

        return events;
    }

    public long getTotalCount() {
        return totalCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getPendingCount() {
        return pendingCount.get();
    }
}
