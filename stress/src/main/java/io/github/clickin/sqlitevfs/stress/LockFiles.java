package io.github.clickin.sqlitevfs.stress;

import io.github.clickin.sqlitevfs.RollbackFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.*;

final class LockFiles implements AutoCloseable {
    final Path path;
    final RollbackFile[] handles;

    LockFiles(int count, boolean firstReadOnly) {
        handles = new RollbackFile[count];
        try {
            path = Files.createTempFile("sqlite-vfs-jcstress-", ".db");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        try {
            for (int i = 0; i < count; i++) {
                handles[i] = RollbackFile.open(path, i == 0 && firstReadOnly);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new IllegalStateException(failure);
        }
    }

    static void require(boolean condition) {
        if (!condition) throw new AssertionError("Rollback lock invariant violated");
    }

    // The arbiter owns teardown, after both actors finish. There are no child
    // processes or executors per jcstress state. Real files and locks are used.
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (RollbackFile handle : handles) {
            if (handle == null) continue;
            try {
                handle.close();
                require(handle.level() == NONE);
            } catch (IOException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    void checkReopen() throws IOException {
        for (RollbackFile handle : handles) handle.close();
        try (RollbackFile reopened = RollbackFile.open(path, false)) {
            require(!reopened.checkReservedLock());
            require(reopened.lock(SHARED));
            require(reopened.lock(RESERVED));
            require(reopened.lock(EXCLUSIVE));
            require(reopened.level() == EXCLUSIVE);
        }
    }
}
