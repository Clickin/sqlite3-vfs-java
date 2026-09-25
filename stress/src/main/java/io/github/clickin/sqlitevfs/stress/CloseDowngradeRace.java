package io.github.clickin.sqlitevfs.stress;

import io.github.clickin.sqlitevfs.RollbackFile;
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
        desc = "Closing the original RO handle preserves the downgraded writer's SHARED lock.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Close lost another handle's lock or downgrade state.")
@State
public class CloseDowngradeRace {
    private final LockFiles files = new LockFiles(3, true);

    public CloseDowngradeRace() {
        try {
            require(files.handles[1].lock(SHARED));
            require(files.handles[1].lock(RESERVED));
            require(files.handles[1].lock(EXCLUSIVE));
        } catch (IOException | RuntimeException | Error failure) {
            try { files.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException(failure);
        }
    }

    @Actor
        public void closeOriginal(III_Result result) {
            try {
                files.handles[0].close();
                result.r1 = files.handles[0].level() == NONE ? 1 : 0;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

    @Actor
        public void downgrade(III_Result result) {
            try {
                files.handles[1].unlock(SHARED);
                result.r2 = files.handles[1].level() == SHARED ? 1 : 0;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

    @Arbiter
        public void check(III_Result result) {
            try {
                try (files) {
                    RollbackFile writer = files.handles[1];
                    RollbackFile arriving = files.handles[2];
                    require(writer.level() == SHARED);
                    require(!arriving.checkReservedLock());
                    require(arriving.lock(SHARED));
                    require(arriving.lock(RESERVED));
                    require(!arriving.lock(EXCLUSIVE));
                    writer.close();
                    require(arriving.lock(EXCLUSIVE));
                    files.checkReopen();
                    result.r3 = 1;
                }
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
}
