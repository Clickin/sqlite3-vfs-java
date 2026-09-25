package io.github.clickin.sqlitevfs;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.github.clickin.sqlitevfs.RollbackFile.Level.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackModelTest {
    @TempDir
    Path directory;

    private enum Grant { CLOSED, OPEN, READER, INTENT, DRAINING, WRITER }
    private enum Result { OK, BUSY, IO_ERROR, INVALID }
    private enum Op { OPEN_RW, OPEN_RO, CLOSE, SHARED, RESERVED, EXCLUSIVE,
        UNLOCK_NONE, UNLOCK_SHARED, CHECK_RESERVED, INVALID_LOCK, INVALID_UNLOCK }
    private static final class Step {
        final int handle;
        final Op op;

        Step(int handle, Op op) {
            this.handle = handle;
            this.op = op;
        }

        @Override public String toString() { return "Step[handle=" + handle + ", op=" + op + "]"; }
    }

    // Specification in terms of compatible grants only: no channels, OS lock
    // objects, descriptor ownership, reader counters, or coordinator state.
    private static final class Model {
        final Grant[] grants = new Grant[5];
        final boolean[] readOnly = new boolean[5];

        Model() {
            Arrays.fill(grants, Grant.CLOSED);
        }

        boolean hasWriter() {
            return Arrays.stream(grants).anyMatch(g -> g == Grant.INTENT
                    || g == Grant.DRAINING || g == Grant.WRITER);
        }

        Result apply(Step step) {
            int id = step.handle;
            Grant current = grants[id];
            switch (step.op) {
                case OPEN_RW:
                case OPEN_RO:
                    grants[id] = Grant.OPEN;
                    readOnly[id] = step.op == Op.OPEN_RO;
                    return Result.OK;
                case CLOSE:
                    grants[id] = Grant.CLOSED;
                    return Result.OK;
                case INVALID_LOCK:
                case INVALID_UNLOCK: return Result.INVALID;
                default: break;
            }
            if (current == Grant.CLOSED) {
                return Result.IO_ERROR;
            }
            if (step.op == Op.CHECK_RESERVED) {
                return hasWriter() ? Result.BUSY : Result.OK;
            }
            if (step.op == Op.UNLOCK_NONE || step.op == Op.UNLOCK_SHARED) {
                if (current != Grant.OPEN) {
                    grants[id] = step.op == Op.UNLOCK_NONE ? Grant.OPEN : Grant.READER;
                }
                return Result.OK;
            }
            Grant requested;
            switch (step.op) {
                case SHARED: requested = Grant.READER; break;
                case RESERVED: requested = Grant.INTENT; break;
                case EXCLUSIVE: requested = Grant.WRITER; break;
                default: throw new AssertionError(step);
            }
            if (current.ordinal() >= requested.ordinal()) {
                return Result.OK;
            }
            if (requested != Grant.READER) {
                if (readOnly[id]) {
                    return Result.IO_ERROR;
                }
                if (current == Grant.OPEN) {
                    return Result.INVALID;
                }
            }
            boolean otherReader = false;
            for (int peer = 0; peer < grants.length; peer++) {
                if (peer == id) {
                    continue;
                }
                Grant held = grants[peer];
                if (held == Grant.DRAINING || held == Grant.WRITER
                        || (requested != Grant.READER && held == Grant.INTENT)) {
                    return Result.BUSY;
                }
                otherReader |= held == Grant.READER || held == Grant.INTENT;
            }
            if (requested == Grant.WRITER && otherReader) {
                grants[id] = Grant.DRAINING;
                return Result.BUSY;
            }
            grants[id] = requested;
            return Result.OK;
        }
    }

    @ParameterizedTest(name = "lock sequence seed={0}")
    @ValueSource(longs = {1, 0x5EED, 0x53514C697465L, 0x25C0FFEE})
    void generatedSequencesAgreeWithCompatibleGrantModel(long seed) throws Exception {
        Path path = Files.createFile(directory.resolve("model-" + seed + ".db"));
        Model model = new Model();
        RollbackFile[] files = new RollbackFile[model.grants.length];
        List<Step> sequence = new ArrayList<>();
        // Ensure the direct-promotion, pending, downgrade and final-close/reopen
        // boundaries are covered even if the subsequent random choices miss them.
        sequence.addAll(List.of(new Step(0, Op.OPEN_RO), new Step(1, Op.OPEN_RW),
                new Step(0, Op.SHARED), new Step(1, Op.SHARED),
                new Step(1, Op.EXCLUSIVE), new Step(2, Op.OPEN_RW),
                new Step(2, Op.SHARED), new Step(0, Op.CLOSE),
                new Step(1, Op.EXCLUSIVE), new Step(1, Op.UNLOCK_SHARED),
                new Step(2, Op.SHARED), new Step(1, Op.CLOSE),
                new Step(2, Op.CLOSE), new Step(0, Op.OPEN_RW)));
        Random random = new Random(seed);
        Op[] operations = Op.values();
        for (int i = 0; i < 2_000; i++) {
            sequence.add(new Step(random.nextInt(files.length),
                    operations[random.nextInt(operations.length)]));
        }
        StringBuilder trace = new StringBuilder("seed=" + seed + '\n');
        try {
            for (int id = 0; id < files.length; id++) {
                files[id] = RollbackFile.open(path, false);
                files[id].close();
            }
            for (int index = 0; index < sequence.size(); index++) {
                Step step = sequence.get(index);
                trace.append(index).append(": ").append(step).append('\n');
                Result expected = model.apply(step);
                assertEquals(expected, apply(files, path, step));
                for (int id = 0; id < files.length; id++) {
                    RollbackFile.Level level;
                    switch (model.grants[id]) {
                        case CLOSED:
                        case OPEN: level = NONE; break;
                        case READER: level = SHARED; break;
                        case INTENT: level = RESERVED; break;
                        case DRAINING: level = PENDING; break;
                        case WRITER: level = EXCLUSIVE; break;
                        default: throw new AssertionError(model.grants[id]);
                    }
                    assertEquals(level, files[id].level(), "handle " + id);
                    if (model.grants[id] != Grant.CLOSED) {
                        assertEquals(model.hasWriter(), files[id].checkReservedLock(),
                                "reserved probe on handle " + id);
                    }
                }
                assertTrue(Arrays.stream(model.grants).filter(g -> g == Grant.INTENT
                        || g == Grant.DRAINING || g == Grant.WRITER).count() <= 1);
                if (Arrays.asList(model.grants).contains(Grant.WRITER)) {
                    assertEquals(1, Arrays.stream(model.grants)
                            .filter(g -> g.ordinal() >= Grant.READER.ordinal()).count());
                }
            }
        } catch (AssertionError | RuntimeException | IOException failure) {
            System.err.print(trace);
            throw new AssertionError("Replayable lock sequence:\n" + trace, failure);
        } finally {
            IOException failure = null;
            for (RollbackFile file : files) {
                if (file != null) {
                    try {
                        file.close();
                    } catch (IOException closeFailure) {
                        if (failure == null) failure = closeFailure;
                        else failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static Result apply(RollbackFile[] files, Path path, Step step) {
        int id = step.handle;
        try {
            if (step.op == Op.OPEN_RO || step.op == Op.OPEN_RW) {
                files[id].close();
                files[id] = RollbackFile.open(path, step.op == Op.OPEN_RO);
                return Result.OK;
            }
            if (step.op == Op.CLOSE) {
                files[id].close();
                return Result.OK;
            }
            switch (step.op) {
                case SHARED: return files[id].lock(SHARED) ? Result.OK : Result.BUSY;
                case RESERVED: return files[id].lock(RESERVED) ? Result.OK : Result.BUSY;
                case EXCLUSIVE: return files[id].lock(EXCLUSIVE) ? Result.OK : Result.BUSY;
                case CHECK_RESERVED: return files[id].checkReservedLock() ? Result.BUSY : Result.OK;
                case UNLOCK_NONE:
                case UNLOCK_SHARED:
                    files[id].unlock(step.op == Op.UNLOCK_NONE ? NONE : SHARED);
                    return Result.OK;
                case INVALID_LOCK:
                    files[id].lock(PENDING);
                    return Result.OK;
                case INVALID_UNLOCK:
                    files[id].unlock(RESERVED);
                    return Result.OK;
                default: throw new AssertionError(step);
            }
        } catch (IOException failure) {
            return Result.IO_ERROR;
        } catch (IllegalArgumentException failure) {
            return Result.INVALID;
        }
    }
}
