package storage.manager.client.job;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe: enqueue() is called from HTTP handler threads, poll() from the client tick thread.
 */
public class JobQueue {
    private final ConcurrentLinkedDeque<Job> queue = new ConcurrentLinkedDeque<>();

    public void enqueue(Job job) {
        queue.addLast(job);
    }

    public Job poll() {
        return queue.pollFirst();
    }

    public int size() {
        return queue.size();
    }

    public List<Job> snapshot() {
        return new ArrayList<>(queue);
    }
}
