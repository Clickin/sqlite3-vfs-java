package io.github.clickin.sqlitevfs;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static io.github.clickin.sqlitevfs.SqliteCodes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ArenaMappingTest {
    @TempDir Path directory;

    @Test
    void aliasesSurviveNonFinalCloseAcrossThreadsButExpireAfterFinalClose() throws Exception {
        NioVfs vfs = new NioVfs();
        Path path = directory.resolve("arena.db");
        NioVfs.OpenResult first = vfs.open(path.toString(), SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
        NioVfs.OpenResult second = vfs.open(path.toString(), SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READWRITE);
        assertEquals(SQLITE_OK, first.code(), vfs.lastError());
        assertEquals(SQLITE_OK, second.code(), vfs.lastError());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(SQLITE_OK, first.file().lock(SQLITE_LOCK_SHARED));
            assertEquals(SQLITE_OK, second.file().lock(SQLITE_LOCK_SHARED));
            NioVfs.ShmResult mapped = first.file().shmMap(0, 32768, true);
            NioVfs.ShmResult attached = second.file().shmMap(0, 32768, true);
            assertEquals(SQLITE_OK, mapped.code(), vfs.lastError());
            assertEquals(SQLITE_OK, attached.code(), vfs.lastError());
            ByteBuffer duplicate = mapped.region().duplicate();
            ByteBuffer slice = duplicate.slice(512, 16);
            IntBuffer typed = slice.asIntBuffer();
            assertEquals(SQLITE_OK, first.file().close());
            workers.submit(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                typed.put(0, 73);
            }).get();
            assertEquals(73, attached.region().duplicate().getInt(512));
            assertEquals(SQLITE_OK, workers.submit(() -> second.file().close()).get());
            assertThrows(IllegalStateException.class, () -> duplicate.get(512));
            assertThrows(IllegalStateException.class, () -> slice.getInt(0));
            assertThrows(IllegalStateException.class, () -> typed.get(0));
        } finally {
            assertEquals(SQLITE_OK, first.file().close());
            assertEquals(SQLITE_OK, second.file().close());
        }
    }
}
