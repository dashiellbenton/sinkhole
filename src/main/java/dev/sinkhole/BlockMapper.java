package dev.sinkhole;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java block state id -> Bedrock block runtime id.
 *
 * Built from GeyserMC's published mappings (MIT, see data/NOTICE): {@code blocks.nbt} maps each Java state to a
 * Bedrock state, {@code block_palette.nbt} is the Bedrock block palette whose index is the runtime id, and
 * {@code java_blocks.txt} (generated from the Java 26.2 data reports) names each Java state's block.
 * Mirrors Geyser's BlockRegistryPopulator.
 */
public final class BlockMapper {
    private final int[] javaToBedrock;
    /** Reverse: Bedrock palette index -> Java state (first Java state mapping to it), and the same keyed by block hash. */
    private final int[] bedrockIndexToJava;
    private final Map<Integer, Integer> bedrockHashToJava = new HashMap<>();
    private final int airRuntimeId;

    public BlockMapper() throws IOException {
        List<NbtMap> palette = readPalette();
        int[] hashes = readPaletteHashes();
        Map<NbtMap, Integer> runtimeIds = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            if (runtimeIds.put(palette.get(i), i) != null) {
                throw new IllegalStateException("Duplicate state in Bedrock palette: " + palette.get(i));
            }
        }

        List<NbtMap> mappings = readMappings();
        String[] javaNames = readJavaNames(mappings.size());

        javaToBedrock = new int[mappings.size()];
        bedrockIndexToJava = new int[palette.size()];
        java.util.Arrays.fill(bedrockIndexToJava, -1);
        int air = -1;
        for (int id = 0; id < mappings.size(); id++) {
            NbtMap entry = mappings.get(id);
            String name = "minecraft:" + entry.getString("bedrock_identifier", javaNames[id].substring("minecraft:".length()));
            NbtMap key = NbtMap.builder().putString("name", name).putCompound("states", entry.getCompound("state")).build();
            Integer runtime = runtimeIds.get(key);
            if (runtime == null) {
                throw new IllegalStateException("No Bedrock runtime id for Java state " + id + " (" + javaNames[id] + "): " + key);
            }
            javaToBedrock[id] = runtime;
            if (bedrockIndexToJava[runtime] < 0) {
                bedrockIndexToJava[runtime] = id;
                bedrockHashToJava.put(hashes[runtime], id);
            }
            if (id == 0) {
                air = runtime;
            }
        }
        airRuntimeId = air;
    }

    public int size() {
        return javaToBedrock.length;
    }

    public int airRuntimeId() {
        return airRuntimeId;
    }

    /**
     * Java block state for a Bedrock runtime id. {@code hashed} is StartGame's blockNetworkIdsHashed: the runtime
     * id is then the block-state hash rather than the palette index. Unknown states become air.
     */
    public int toJava(int bedrockRuntimeId, boolean hashed) {
        Integer java = hashed ? bedrockHashToJava.get(bedrockRuntimeId)
                : (bedrockRuntimeId >= 0 && bedrockRuntimeId < bedrockIndexToJava.length && bedrockIndexToJava[bedrockRuntimeId] >= 0
                ? Integer.valueOf(bedrockIndexToJava[bedrockRuntimeId]) : null);
        return java == null ? 0 : java;
    }

    private static int[] readPaletteHashes() throws IOException {
        try (NBTInputStream in = NbtUtils.createGZIPReader(resource("/data/block_palette.nbt"))) {
            NbtMap root = (NbtMap) in.readTag();
            List<NbtMap> blocks = root.getList("blocks", NbtType.COMPOUND);
            int[] out = new int[blocks.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = blocks.get(i).getInt("network_id");
            }
            return out;
        }
    }

    /** Bedrock runtime id for a Java block state id (air if unknown). */
    public int toBedrock(int javaState) {
        return javaState >= 0 && javaState < javaToBedrock.length ? javaToBedrock[javaState] : airRuntimeId;
    }

    private static List<NbtMap> readPalette() throws IOException {
        try (NBTInputStream in = NbtUtils.createGZIPReader(resource("/data/block_palette.nbt"))) {
            NbtMap root = (NbtMap) in.readTag();
            List<NbtMap> out = new ArrayList<>();
            for (NbtMap block : root.getList("blocks", NbtType.COMPOUND)) {
                // Strip everything that isn't part of a state's identity (same as Geyser).
                NbtMapBuilder b = block.toBuilder();
                b.remove("version");
                b.remove("name_hash");
                b.remove("network_id");
                b.remove("block_id");
                out.add(b.build());
            }
            return out;
        }
    }

    private static List<NbtMap> readMappings() throws IOException {
        try (NBTInputStream in = NbtUtils.createGZIPReader(resource("/data/blocks.nbt"))) {
            NbtMap root = (NbtMap) in.readTag();
            return new ArrayList<>(root.getList("bedrock_mappings", NbtType.COMPOUND));
        }
    }

    private static String[] readJavaNames(int count) throws IOException {
        String[] names = new String[count];
        try (BufferedReader r = new BufferedReader(new InputStreamReader(resource("/data/java_blocks.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(" ");
                for (int i = Integer.parseInt(p[0]); i <= Integer.parseInt(p[1]); i++) {
                    names[i] = p[2];
                }
            }
        }
        return names;
    }

    private static InputStream resource(String path) throws IOException {
        InputStream s = BlockMapper.class.getResourceAsStream(path);
        if (s == null) {
            throw new IOException("Missing resource " + path);
        }
        return s;
    }
}
