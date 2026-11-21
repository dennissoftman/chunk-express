package com.chunkexpress.integration.neoforge;

import com.chunkexpress.util.Logs;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import java.util.Map;
import java.util.TreeMap;

/** Reads the installed mods from FML. Usable from the mixin plugin (before mods are constructed). */
public final class InstalledMods {
    private InstalledMods() {
    }

    /** Mod id to version. Empty (and a warning) if FML's list is unavailable. */
    public static Map<String, String> fromLoadingModList() {
        Map<String, String> mods = new TreeMap<>();
        try {
            LoadingModList list = LoadingModList.get();
            if (list == null) {
                Logs.LOG.warn("FML mod list not available; Chunk Express compatibility rules cannot run. "
                        + "All probes stay allowed.");
                return mods;
            }
            for (ModInfo info : list.getMods()) {
                mods.put(info.getModId(), String.valueOf(info.getVersion()));
            }
        } catch (RuntimeException | LinkageError e) {
            Logs.LOG.warn("Could not read the FML mod list; Chunk Express compatibility rules cannot run.", e);
        }
        return mods;
    }
}
