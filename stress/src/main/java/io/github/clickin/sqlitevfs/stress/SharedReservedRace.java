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
@Outcome(id = {"2, 1, 1", "1, 2, 1"}, expect = Expect.ACCEPTABLE,
        desc = "Both readers coexist and exactly one retains RESERVED.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Lost reader, two writers, or invalid final lock state.")
@State
public class SharedReservedRace {
    private final LockFiles files = new LockFiles(3, false);

    @Actor
    public void first(III_Result result) throws IOException {
        result.r1 = acquire(files.handles[0]);
    }

    @Actor
    public void second(III_Result result) throws IOException {
        result.r2 = acquire(files.handles[1]);
    }

    private static int acquire(RollbackFile file) throws IOException {
        if (!file.lock(SHARED)) return 0;
        return file.lock(RESERVED) ? 2 : 1;
    }

    @Arbiter
    public void check(III_Result result) throws IOException {
        try (files) {
            RollbackFile winner = files.handles[0].level() == RESERVED
                    ? files.handles[0] : files.handles[1];
            RollbackFile reader = winner == files.handles[0]
                    ? files.handles[1] : files.handles[0];
            require(winner.level() == RESERVED && reader.level() == SHARED);
            require(files.handles[2].checkReservedLock());
            require(!winner.lock(EXCLUSIVE));
            require(winner.level() == PENDING);
            require(!files.handles[2].lock(SHARED));
            reader.close();
            require(winner.lock(EXCLUSIVE));
            files.checkReopen();
            result.r3 = 1;
        }
    }
}
