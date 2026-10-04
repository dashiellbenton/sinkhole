package dev.sinkhole;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Name <-> id tables for Java 26.2 registries and the Bedrock -> Java item name map (from GeyserMC mappings). */
public final class GameData {
    private final Map<String, Integer> javaItemIds = new HashMap<>();
    private final Map<String, String> bedrockToJavaItem = new HashMap<>();

    public GameData() throws IOException {
        JsonObject regs = read("/data/java_registries.json");
        for (var e : regs.getAsJsonObject("item").entrySet()) {
            javaItemIds.put(e.getKey(), e.getValue().getAsInt());
        }
        for (var e : read("/data/bedrock_to_java_items.json").entrySet()) {
            bedrockToJavaItem.put(e.getKey(), e.getValue().getAsString());
        }
    }

    private static JsonObject read(String resource) throws IOException {
        try (Reader r = new InputStreamReader(GameData.class.getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Java item protocol id for a Bedrock item identifier + damage value, or -1 if there is no equivalent. */
    public int javaItemId(String bedrockIdentifier, int damage) {
        String name = bedrockToJavaItem.get(bedrockIdentifier + ":" + damage);
        if (name == null) {
            name = bedrockToJavaItem.get(bedrockIdentifier + ":0");
        }
        if (name == null) {
            name = bedrockIdentifier; // most identifiers are shared
        }
        Integer id = javaItemIds.get(name);
        return id == null ? -1 : id;
    }
}
