package com.github.berrywang1996.netty.spring.web.context;

/**
 * Strategy interface for submitting handler work onto the shared bounded execution model.
 *
 * <p>Implementations are expected to enforce admission control (e.g. semaphore permits)
 * and delegate execution to a bounded thread pool. If the task cannot be accepted,
 * a {@link java.util.concurrent.RejectedExecutionException} should be thrown.
 *
 * @author berrywang1996
 * @since V1.0.0
 * @see WebMappingSupporter
 * @see HandlerSubmitterAware
 */
public interface HandlerSubmitter {

    /**
     * Submits a handler task for asynchronous execution on the shared thread pool.
     *
     * @param runnable the handler work to execute
     * @throws java.util.concurrent.RejectedExecutionException if the task cannot be accepted
     *         due to permit exhaustion or thread pool saturation
     */
    void submitHandle(Runnable runnable);

    /**
     * A task owning resources that must be released if accepted work is discarded
     * before execution during forced shutdown. The submitter invokes either
     * {@link #run()} or {@link #discard()}, never both. Submission rejection does
     * not invoke {@code discard}; the caller still owns rejected work.
     */
    interface DiscardableTask extends Runnable {
        /** Releases resources without executing the discarded business handler. */
        void discard();
    }

}
