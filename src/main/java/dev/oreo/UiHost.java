package dev.oreo;

/**
 * A UI backend that renders an Ow widget tree and routes events back into the
 * interpreter. Two implementations exist: {@link UiRuntime} (modern web page in
 * the browser) and {@link SwingUiRuntime} (modern flat native desktop window).
 */
public interface UiHost {
    /** Starts the UI and blocks until the window/server is stopped. */
    void run(String renderFn, String title);

    /** Requests a graceful stop (e.g. from an in-app quit button). */
    void requestStop();

    /** Forcibly shuts down and releases a blocked {@link #run}. Safe from any thread. */
    void shutdown();
}
