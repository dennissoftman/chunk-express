package com.chunkexpress.generation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The surface rule and condition types a dimension actually uses, read from its surface rule
 * encoded as JSON (every rule and condition encodes its registry id under "type").
 *
 * <p>This is what the offload gate checks: a mod may register types that no dimension uses (a
 * library such as Lithostitched registers several), and those never run.
 */
public final class RuleTypes {
    private RuleTypes() {
    }

    /** Every "type" value anywhere in the tree, sorted. */
    public static Set<String> collect(JsonElement tree) {
        Set<String> types = new TreeSet<>();
        walk(tree, types);
        return types;
    }

    /** The types outside the minecraft namespace (ids without a namespace are minecraft's). */
    public static Set<String> nonVanilla(Set<String> types) {
        Set<String> custom = new TreeSet<>();
        for (String type : types) {
            int colon = type.indexOf(':');
            if (colon >= 0 && !type.substring(0, colon).equals("minecraft")) {
                custom.add(type);
            }
        }
        return custom;
    }

    private static void walk(JsonElement element, Set<String> types) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> walk(e, types));
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getKey().equals("type") && entry.getValue() instanceof JsonPrimitive p && p.isString()) {
                types.add(p.getAsString());
            } else {
                walk(entry.getValue(), types);
            }
        }
    }
}
