package io.github.clickin.sqlitevfs.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

import java.io.IOException;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.*;
import static io.github.clickin.sqlitevfs.stress.LockFiles.require;

@JCStressTest
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE,
        desc = "Concurrent close/unlock release the last physical reader and permit a fresh writer.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Leaked shared ownership or invalid last-reader release.")
@State
public class LastReaderRace {
    private final LockFiles files = new LockFiles(3, false);

    public LastReaderRace() {
        try {
            require(files.handles[0].lock(SHARED));
            require(files.handles[1].lock(SHARED));
        } catch (IOException | RuntimeException | Error failure) {
            try { files.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException(failure);
        }
    }

    @Actor
    public void unlock(III_Result result) throws IOException {
        files.handles[0].unlock(NONE);
        result.r1 = files.handles[0].level() == NONE ? 1 : 0;
    }

    @Actor
    public void close(III_Result result) throws IOException {
        files.handles[1].close();
        result.r2 = files.handles[1].level() == NONE ? 1 : 0;
    }

    @Arbiter
    public void check(III_Result result) throws IOException {
        try (files) {
            require(!files.handles[2].checkReservedLock());
            require(files.handles[2].lock(SHARED));
            require(files.handles[2].lock(EXCLUSIVE));
            require(!files.handles[0].lock(SHARED));
            files.handles[2].unlock(NONE);
            require(files.handles[0].lock(SHARED));
            files.checkReopen();
            result.r3 = 1;
        }
    }
}
