package dev.sinkhole;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinkholeTests {
    @Test
    void configDefaultsAndParsing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        SinkholeConfig.writeDefault(file);
        SinkholeConfig c = SinkholeConfig.load(file);
        assertEquals(35653, c.port);
        assertFalse(c.hasServer(), "server has no default");

        Files.writeString(file, "port: 1234\nserver: \"play.example.net:19133\"\n");
        c = SinkholeConfig.load(file);
        assertEquals(1234, c.port);
        assertEquals("play.example.net", c.serverHost());
        assertEquals(19133, c.serverPort());

        Files.writeString(file, "server: \"play.example.net\"\n");
        c = SinkholeConfig.load(file);
        assertEquals(19132, c.serverPort(), "Bedrock default port");
        assertEquals(35653, c.port);
    }

    @Test
    void usernameRewriting() {
        assertEquals("hi JavaDude!", Bridge.rename("hi BedrockGuy!", "BedrockGuy", "JavaDude"));
        assertEquals("hi javadude", Bridge.rename("hi BEDROCKGUY", "BedrockGuy", "javadude"));
        assertEquals("NotBedrockGuyX", Bridge.rename("NotBedrockGuyX", "BedrockGuy", "JavaDude"), "whole words only");
        assertEquals("x", Bridge.rename("x", null, "JavaDude"));
    }

    @Test
    void blockMappingRoundTrips() throws Exception {
        BlockMapper m = new BlockMapper();
        assertEquals(35723, m.size(), "Java 26.3 block states");
        // stone is Java state 1 on every version
        assertEquals(1, m.toJava(m.toBedrock(1), false));
        assertEquals(1, m.toJava(FakeBedrockServer.hashOf("minecraft:stone"), true));
        assertEquals(0, m.toJava(m.toBedrock(0), false), "air");
        // every Java state maps to a real Bedrock state
        for (int i = 0; i < m.size(); i++) {
            assertTrue(m.toBedrock(i) >= 0);
        }
    }

    @Test
    void itemMapping() throws Exception {
        GameData d = new GameData();
        assertTrue(d.javaItemId("minecraft:diamond_sword", 0) > 0);
        assertEquals(-1, d.javaItemId("minecraft:definitely_not_an_item", 0));
    }

    @Test
    void registriesLoad() throws Exception {
        Registries r = new Registries();
        assertFalse(r.registryPackets().isEmpty());
        assertNotEquals(0, r.idOf("minecraft:dimension_type", "minecraft:the_nether") + r.idOf("minecraft:dimension_type", "minecraft:the_end"));
        assertFalse(r.bedrockBiomeIds().isEmpty());
    }

    @Test
    void bedrockChunkBecomesJavaChunk() throws Exception {
        BlockMapper blocks = new BlockMapper();
        Registries reg = new Registries();
        BedrockChunks chunks = new BedrockChunks(blocks, reg.idOf("minecraft:worldgen/biome", "minecraft:plains"), reg.bedrockBiomeIds());
        chunks.setHashed(true);
        int stone = FakeBedrockServer.hashOf("minecraft:stone");
        int air = FakeBedrockServer.hashOf("minecraft:air");

        ByteBuf data = Unpooled.buffer();
        for (int sub = 0; sub < 24; sub++) {
            data.writeByte(9);
            data.writeByte(1);
            data.writeByte(sub - 4);
            int[] idx = new int[4096];
            List<Integer> palette = new ArrayList<>(List.of(air, stone));
            if (sub == 0) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        idx[(x << 8) | (z << 4)] = 1; // y == 0 of the lowest sub-chunk
                    }
                }
            }
            FakeBedrockServer.writeStorage(data, idx, palette);
        }
        FakeBedrockServer.writeStorage(data, new int[4096], List.of(1));
        for (int i = 1; i < 24; i++) {
            data.writeByte(0xFF);
        }

        ClientboundLevelChunkWithLightPacket packet = chunks.translate(3, -2, data, 24, 24, true, 0);
        assertEquals(3, packet.getX());
        assertEquals(-2, packet.getZ());
        ByteBuf in = Unpooled.wrappedBuffer(packet.getChunkData());
        ChunkSection first = MinecraftTypes.readChunkSection(in, 7, 16);
        assertEquals(1, first.getBlock(5, 0, 7), "stone at the bottom");
        assertEquals(0, first.getBlock(5, 1, 7), "air above it");
    }
}
