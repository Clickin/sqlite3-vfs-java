package io.github.clickin.sqlitevfs;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;

/** Optional newer-JDK test capabilities without a newer class-file baseline. */
public final class JdkSupport {
    private JdkSupport() {}

    public static boolean hasVirtualThreads() {
        return Runtime.version().feature() >= 21;
    }

    public static ExecutorService newVirtualThreadExecutor() {
        try {
            return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw unavailable(failure);
        }
    }

    public static ThreadFactory virtualThreadFactory(String prefix) {
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Class<?> type = Class.forName("java.lang.Thread$Builder");
            builder = type.getMethod("name", String.class, long.class).invoke(builder, prefix, 0L);
            return (ThreadFactory) type.getMethod("factory").invoke(builder);
        } catch (ReflectiveOperationException failure) {
            throw unavailable(failure);
        }
    }

    public static boolean isVirtual(Thread thread) {
        if (!hasVirtualThreads()) return false;
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException failure) {
            throw unavailable(failure);
        }
    }

    public static void addNativeAccessOptions(List<String> command) {
        if (Runtime.version().feature() >= 17) command.add("--enable-native-access=ALL-UNNAMED");
        if (Runtime.version().feature() >= 24) command.add("--illegal-native-access=deny");
    }

    /** Java 11 Windows timed wait/isAlive can observe an exit code before handle teardown. */
    public static boolean waitForExit(Process process, long timeout, TimeUnit unit)
            throws InterruptedException {
        // Unconditional waitFor reaches WaitForMultipleObjects on the process handle.
        FutureTask<Integer> completion = new FutureTask<>(process::waitFor);
        Thread waiter = new Thread(completion, "child-exit-" + process.pid());
        waiter.setDaemon(true);
        waiter.start();
        try {
            completion.get(timeout, unit);
            return true;
        } catch (TimeoutException failure) {
            return false;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Waiting for child exit", failure.getCause());
        } finally {
            completion.cancel(true);
        }
    }

    private static IllegalStateException unavailable(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
        return new IllegalStateException("Virtual threads require JDK 21 or later", cause);
    }
}
