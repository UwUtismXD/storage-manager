package storage.manager.client.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 tests for the queue itself. The interesting bits are FIFO + thread safety + the
 * independence of the snapshot, since snapshot() is called from HTTP threads while poll()
 * runs on the client tick thread.
 */
class JobQueueTest {

    @Test
    void newQueueIsEmpty() {
        JobQueue q = new JobQueue();
        assertEquals(0, q.size());
        assertNull(q.poll());
        assertTrue(q.snapshot().isEmpty());
    }

    @Test
    void enqueuePollIsFifo() {
        JobQueue q = new JobQueue();
        Job a = Job.sortInput();
        Job b = Job.randomize();
        Job c = Job.scanRegion();
        q.enqueue(a);
        q.enqueue(b);
        q.enqueue(c);
        assertSame(a, q.poll());
        assertSame(b, q.poll());
        assertSame(c, q.poll());
        assertNull(q.poll());
    }

    @Test
    void clearDropsEverything() {
        JobQueue q = new JobQueue();
        q.enqueue(Job.sortInput());
        q.enqueue(Job.randomize());
        q.clear();
        assertEquals(0, q.size());
        assertNull(q.poll());
    }

    @Test
    void snapshotIsIndependentCopy() {
        JobQueue q = new JobQueue();
        Job enqueued = Job.sortInput();
        q.enqueue(enqueued);
        List<Job> snap = q.snapshot();
        assertEquals(1, snap.size());
        // Mutating the returned list must not touch the live queue - the web UI consumes
        // snapshots on HTTP threads while poll() runs on the tick thread, so a shared list
        // would race on every render.
        snap.clear();
        assertEquals(1, q.size());
        // Hold the reference: Job.sortInput() always allocates a fresh instance, so
        // assertSame against a second call would fail on identity even though the queue
        // is behaving correctly.
        assertSame(enqueued, q.poll());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentEnqueuePollDoesNotLoseJobs() throws InterruptedException {
        // Real workload shape: many HTTP threads enqueueing while the tick thread polls.
        // 4 producers, 4 consumers, 1000 jobs each - total polled must equal total enqueued.
        JobQueue q = new JobQueue();
        int producers = 4;
        int consumers = 4;
        int perProducer = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        AtomicInteger polled = new AtomicInteger();

        for (int i = 0; i < producers; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perProducer; j++) {
                        q.enqueue(Job.sortInput());
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    producersDone.countDown();
                }
            }, "producer-" + i).start();
        }

        Thread[] consumerThreads = new Thread[consumers];
        for (int i = 0; i < consumers; i++) {
            consumerThreads[i] = new Thread(() -> {
                try {
                    start.await();
                    while (true) {
                        Job job = q.poll();
                        if (job == null) {
                            // Producers finished AND queue drained: nothing more to do.
                            if (producersDone.getCount() == 0 && q.size() == 0) {
                                return;
                            }
                            // Brief yield so the loop doesn't burn a core while waiting.
                            Thread.sleep(0, 100_000);
                            continue;
                        }
                        polled.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            }, "consumer-" + i);
            consumerThreads[i].start();
        }

        start.countDown();
        producersDone.await();
        // Last tick of grace for in-flight polls to land before the assertion.
        Thread.sleep(50);
        for (Thread t : consumerThreads) {
            t.join(1000);
        }
        assertEquals(producers * perProducer, polled.get(), "every enqueued job was polled exactly once");
        assertEquals(0, q.size(), "queue fully drained");
    }
}
