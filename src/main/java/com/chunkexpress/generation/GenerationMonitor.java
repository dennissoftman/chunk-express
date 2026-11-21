package com.chunkexpress.generation;

import com.chunkexpress.metrics.LatencyHistogram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Time spent in each world-generation stage ({@code ChunkStatus}), fed by the observe-only
 * {@code ChunkStepMixin}. Only steps that really generate count; steps that merely pass an
 * already-generated chunk through (loaded from disk) are ignored.
 *
 * <p>Two numbers per stage, because vanilla 1.21.1 splits the work across executors:
 * <ul>
 *   <li><b>inline</b> - time inside {@code ChunkStep.apply} on the calling thread. For most stages
 *       (structure starts, surface, carvers, features, spawn) this is all of the work, and it runs
 *       on the dimension's single "worldgen" mailbox, one chunk at a time. Its sum is how busy that
 *       serial lane was.</li>
 *   <li><b>to completion</b> - until the stage's future completes. Adds work handed to other
 *       executors (noise and biomes on the background pool, light on the light mailbox, the full
 *       stage on the server thread) including time queued there.</li>
 * </ul>
 *
 * <p>Thread safety: safe from any thread.
 */
public final class GenerationMonitor {
    /** Vanilla has 12 statuses; leave room, but stay bounded. */
    static final int MAX_STAGES = 64;

    private final AtomicReferenceArray<Stage> stages = new AtomicReferenceArray<>(MAX_STAGES);

    public void stageFinished(int index, String name, long inlineNanos, long totalNanos, boolean failed) {
        if (index < 0 || index >= MAX_STAGES) {
            return;
        }
        Stage stage = this.stages.get(index);
        if (stage == null) {
            this.stages.compareAndSet(index, null, new Stage(name));
            stage = this.stages.get(index);
        }
        stage.count.increment();
        if (failed) {
            stage.failed.increment();
        }
        stage.inlineSum.add(inlineNanos);
        stage.inline.record(inlineNanos);
        stage.total.record(totalNanos);
    }

    public void reset() {
        for (int i = 0; i < MAX_STAGES; i++) {
            Stage stage = this.stages.get(i);
            if (stage != null) {
                stage.reset();
            }
        }
    }

    /** Stages in generation order. */
    public List<StageSnapshot> snapshot() {
        List<StageSnapshot> result = new ArrayList<>();
        for (int i = 0; i < MAX_STAGES; i++) {
            Stage stage = this.stages.get(i);
            if (stage != null) {
                result.add(new StageSnapshot(stage.name, stage.count.sum(), stage.failed.sum(), stage.inlineSum.sum(),
                        stage.inline.snapshot(), stage.total.snapshot()));
            }
        }
        return result;
    }

    private static final class Stage {
        final String name;
        final LongAdder count = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder inlineSum = new LongAdder();
        final LatencyHistogram inline = new LatencyHistogram();
        final LatencyHistogram total = new LatencyHistogram();

        Stage(String name) {
            this.name = name;
        }

        void reset() {
            this.count.reset();
            this.failed.reset();
            this.inlineSum.reset();
            this.inline.reset();
            this.total.reset();
        }
    }

    public record StageSnapshot(String name, long count, long failed, long inlineSumNanos,
                                LatencyHistogram.Snapshot inline, LatencyHistogram.Snapshot total) {
    }
}
