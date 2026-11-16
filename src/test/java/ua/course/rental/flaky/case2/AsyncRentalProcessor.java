package ua.course.rental.flaky.case2;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


public final class AsyncRentalProcessor implements AutoCloseable {
    public enum Status { PENDING, CONFIRMED }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, Status> statuses = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final long processingDelayMs;

    public AsyncRentalProcessor(long processingDelayMs) {
        this.processingDelayMs = processingDelayMs;
    }

    public long initiateRental(long equipmentId, String reference) {
        long id = ids.incrementAndGet();
        statuses.put(id, Status.PENDING);
        executor.schedule(() -> statuses.put(id, Status.CONFIRMED), processingDelayMs, TimeUnit.MILLISECONDS);
        return id;
    }

    public Status getRentalStatus(long id) {
        return statuses.get(id);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
