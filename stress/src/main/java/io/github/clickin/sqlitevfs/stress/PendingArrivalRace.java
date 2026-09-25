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
        desc = "PENDING blocks arrivals while the last other reader leaves; promotion then succeeds.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Arrival bypassed PENDING or departure lost writer state.")
@State
public class PendingArrivalRace {
    private final LockFiles files = new LockFiles(3, false);

    public PendingArrivalRace() {
        try {
            require(files.handles[0].lock(SHARED));
            require(files.handles[1].lock(SHARED));
            require(!files.handles[0].lock(EXCLUSIVE));
            require(files.handles[0].level() == PENDING);
        } catch (IOException | RuntimeException | Error failure) {
            try { files.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException(failure);
        }
    }

    @Actor
        public void departure(III_Result result) {
            try {
                files.handles[1].close();
                result.r1 = files.handles[1].level() == NONE ? 1 : 0;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

    @Actor
        public void arrival(III_Result result) {
            try {
                result.r2 = files.handles[2].lock(SHARED) ? 0 : 1;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

    @Arbiter
        public void check(III_Result result) {
            try {
                try (files) {
                    require(files.handles[0].level() == PENDING);
                    require(files.handles[0].lock(EXCLUSIVE));
                    require(!files.handles[2].lock(SHARED));
                    files.handles[0].unlock(SHARED);
                    require(files.handles[2].lock(SHARED));
                    files.checkReopen();
                    result.r3 = 1;
                }
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
}
