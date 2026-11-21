package com.chunkexpress.integration.mixin.hook.probe;

import com.chunkexpress.integration.ProbeHooks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.nio.file.Path;

/**
 * Observe-only probes for two silent loss paths in vanilla region files.
 *
 * <p><b>Damaged entries.</b> {@code getChunkDataInputStream} returns null both for "no chunk
 * stored here" and for several kinds of damage (truncated header or stream, zero or negative
 * length, unknown compression). Vanilla only logs the damage and then treats the chunk as absent:
 * it is generated again and the damaged entry is overwritten on the next save, without passing
 * through {@code reportChunkLoadFailure}. Null while the header still has an offset for the chunk
 * means the entry exists but could not be read.
 *
 * <p><b>Discarded header entries.</b> When a region file is opened, header entries that overlap
 * the header, have zero size or point past the end of the file are set to zero in memory (each
 * {@code offsets.put(index, 0)} in the constructor). The next write to any chunk of that region
 * rewrites the header and makes the loss permanent. Counted per file and reported once it is open.
 */
@Mixin(RegionFile.class)
abstract class RegionFileMixin {
    private static final String CONSTRUCTOR =
            "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;"
                    + "Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V";

    @Shadow
    @Final
    RegionStorageInfo info;

    @Shadow
    @Final
    private Path path;

    @Unique
    private int chunkexpress$discardedEntries;

    @Inject(method = CONSTRUCTOR, at = @At(value = "INVOKE", target = "Ljava/nio/IntBuffer;put(II)Ljava/nio/IntBuffer;"))
    private void chunkexpress$entryDiscarded(CallbackInfo ci) {
        this.chunkexpress$discardedEntries++;
    }

    @Inject(method = CONSTRUCTOR, at = @At("RETURN"))
    private void chunkexpress$opened(CallbackInfo ci) {
        if (this.chunkexpress$discardedEntries > 0) {
            ProbeHooks.regionEntriesDiscarded(this.info, this.path, this.chunkexpress$discardedEntries);
        }
    }

    @Inject(method = "getChunkDataInputStream", at = @At("RETURN"))
    private void chunkexpress$entryRead(ChunkPos pos, CallbackInfoReturnable<DataInputStream> cir) {
        if (cir.getReturnValue() == null && ((RegionFile) (Object) this).hasChunk(pos)) {
            ProbeHooks.unreadableRegionEntry(this.info, this.path, pos);
        }
    }
}
