package com.linuxops.task;

import com.linuxops.model.Host;
import com.linuxops.security.CredentialSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded batch executor. Each host task is isolated; one failure never aborts peers. */
public final class TaskManager implements AutoCloseable {
    @FunctionalInterface
    public interface HostOperation {
        Integer execute(Host host, CredentialSnapshot credential, CancellationToken token) throws Exception;
    }

    public interface Listener {
        default void onUpdate(TaskResult result) { }
        default void onBatchFinished(int total, int completed, int succeeded, int failed, int cancelled) { }
    }

    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
        public void throwIfCancelled() throws InterruptedException {
            if (isCancelled()) throw new InterruptedException("Task cancelled");
        }
        private void cancel() { cancelled.set(true); }
    }

    public static final class BatchHandle {
        private final String id = UUID.randomUUID().toString();
        private final CancellationToken token = new CancellationToken();
        private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private final int total;
        private BatchHandle(int total) { this.total = total; }
        public String id() { return id; }
        public int total() { return total; }
        public int completed() { return completed.get(); }
        public int succeeded() { return succeeded.get(); }
        public int failed() { return failed.get(); }
        public int cancelled() { return cancelled.get(); }
        public void cancel() {
            // Do not cancel queued Futures directly: a cancelled-before-start task
            // cannot decrement its wave latch. Let queued tasks start, observe the token,
            // and finish their bookkeeping promptly. Running SSH operations check the token.
            token.cancel();
        }
    }

    private final ThreadPoolExecutor executor;
    private final int batchSize;

    public TaskManager(int maxConcurrency, int queueCapacity, int batchSize) {
        if (maxConcurrency < 1 || queueCapacity < 1 || batchSize < 1)
            throw new IllegalArgumentException("concurrency, queue capacity and batch size must be positive");
        this.batchSize = batchSize;
        this.executor = new ThreadPoolExecutor(maxConcurrency, maxConcurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "linuxops-task"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public BatchHandle submitBatch(List<Host> hosts, char[] password, HostOperation operation, Listener listener) {
        if (hosts == null || hosts.isEmpty()) throw new IllegalArgumentException("no target hosts");
        if (operation == null) throw new IllegalArgumentException("operation is required");
        Listener safeListener = listener == null ? new Listener() { } : listener;
        BatchHandle handle = new BatchHandle(hosts.size());
        char[] master = password == null ? new char[0] : password.clone();

        // Submit in bounded waves. This keeps queue pressure predictable for 100-500 hosts.
        Thread coordinator = new Thread(() -> {
            try {
                for (int from = 0; from < hosts.size() && !handle.token.isCancelled(); from += batchSize) {
                    int to = Math.min(from + batchSize, hosts.size());
                    CountDownLatch wave = new CountDownLatch(to - from);
                    for (Host host : hosts.subList(from, to)) {
                        if (handle.token.isCancelled()) { wave.countDown(); continue; }
                        CredentialSnapshot credential = new CredentialSnapshot(master);
                        try {
                            Future<?> future = executor.submit(() -> runOne(handle, host, credential, operation, safeListener, wave));
                            handle.futures.add(future);
                        } catch (RejectedExecutionException ex) {
                            credential.close();
                            publish(handle, host, TaskState.FAILED, "Task queue is full", null, null, safeListener);
                            handle.failed.incrementAndGet(); handle.completed.incrementAndGet(); wave.countDown();
                        }
                    }
                    wave.await();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); handle.cancel();
            } finally {
                java.util.Arrays.fill(master, '\0');
                safeListener.onBatchFinished(handle.total, handle.completed(), handle.succeeded(), handle.failed(), handle.cancelled());
            }
        }, "linuxops-batch-" + handle.id.substring(0, 8));
        coordinator.setDaemon(true);
        coordinator.start();
        return handle;
    }

    private void runOne(BatchHandle handle, Host host, CredentialSnapshot credential,
                        HostOperation operation, Listener listener, CountDownLatch wave) {
        Instant start = Instant.now();
        try (credential) {
            if (handle.token.isCancelled()) throw new InterruptedException("Batch cancelled");
            publish(handle, host, TaskState.RUNNING, "Running", null, start, listener);
            Integer exit = operation.execute(host, credential, handle.token);
            if (handle.token.isCancelled()) {
                handle.cancelled.incrementAndGet();
                publish(handle, host, TaskState.CANCELLED, "Cancelled", exit, start, listener);
            } else if (exit == null || exit == 0) {
                handle.succeeded.incrementAndGet();
                publish(handle, host, TaskState.SUCCEEDED, "Completed", exit, start, listener);
            } else {
                handle.failed.incrementAndGet();
                publish(handle, host, TaskState.FAILED, "Remote exit code: " + exit, exit, start, listener);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); handle.cancelled.incrementAndGet();
            publish(handle, host, TaskState.CANCELLED, "Cancelled", null, start, listener);
        } catch (Exception ex) {
            handle.failed.incrementAndGet();
            publish(handle, host, TaskState.FAILED, safeMessage(ex), null, start, listener);
        } finally {
            handle.completed.incrementAndGet(); wave.countDown();
        }
    }

    private static String safeMessage(Exception ex) {
        String m = ex.getMessage(); return m == null || m.isBlank() ? ex.getClass().getSimpleName() : m;
    }

    private static void publish(BatchHandle batch, Host host, TaskState state, String message,
                                Integer exit, Instant started, Listener listener) {
        listener.onUpdate(new TaskResult(batch.id, host.id(), state, message, exit, started,
                state == TaskState.RUNNING || state == TaskState.QUEUED ? null : Instant.now()));
    }

    @Override public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
}
