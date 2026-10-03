package dev.sinkhole;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bedrock item runtime ids (GeyserMC runtime_item_states for 1.26.50), sent in StartGame. */
final class ItemDefinitions {
    private ItemDefinitions() {
    }

    static List<ItemDefinition> load() {
        try (Reader r = new InputStreamReader(ItemDefinitions.class.getResourceAsStream("/data/runtime_item_states.json"), StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(r).getAsJsonArray();
            List<ItemDefinition> out = new ArrayList<>(array.size());
            for (JsonElement e : array) {
                var o = e.getAsJsonObject();
                out.add(new SimpleItemDefinition(o.get("name").getAsString(), o.get("id").getAsInt(), o.get("componentBased").getAsBoolean()));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load item definitions", e);
        }
    }
}
