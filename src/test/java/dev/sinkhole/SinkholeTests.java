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
        assertEquals("auto", c.transport);
        assertFalse(c.hasServer(), "server has no default");

        Files.writeString(file, "port: 1234\ntransport: NetherNet\nserver: \"play.example.net:19133\"\n");
        c = SinkholeConfig.load(file);
        assertEquals(1234, c.port);
        assertEquals("nethernet", c.transport);
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

    @Test
    void loginJsonMatchesWhatClientsSend() throws Exception {
        AuthService auth = new AuthService(Path.of("unused.json"), "1.26.50");
        auth.useOffline("Tester");
        AuthService.Identity id = auth.identity();
        var payload = id.payload();
        var m = payload.getClass().getDeclaredMethod("json");
        m.setAccessible(true);
        var json = com.google.gson.JsonParser.parseString((String) m.invoke(payload)).getAsJsonObject();
        assertEquals(2, json.get("AuthenticationType").getAsInt(), "self-signed");
        assertEquals("{\"chain\":[\"\"]}", json.get("Certificate").getAsString());
        String[] token = json.get("Token").getAsString().split("\\.");
        assertEquals(3, token.length);
        var claims = com.google.gson.JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(token[1]))).getAsJsonObject();
        assertEquals("Tester", claims.get("xname").getAsString());
        assertEquals(java.util.Base64.getEncoder().encodeToString(id.key().getPublic().getEncoded()), claims.get("cpk").getAsString());
        // client data: the device id must be 32 lowercase hex characters for Windows
        var data = com.google.gson.JsonParser.parseString(ClientData.payload(id, "127.0.0.1:19132", "1.26.50")).getAsJsonObject();
        assertTrue(data.get("DeviceId").getAsString().matches("[0-9a-f]{32}") || id.deviceOs() != 7);
    }

    @Test
    void nethernetFramingHasNoFrameId() {
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new NetherNetFraming());
        ByteBuf wire = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02, 0x03});
        assertTrue(channel.writeInbound(wire));
        org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper batch = channel.readInbound();
        assertEquals(3, batch.getCompressed().readableBytes(), "the message is the batch itself");
        batch.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void identityAssertionIsAddedToTheOffer() throws Exception {
        var key = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils.createKeyPair();
        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=fingerprint:sha-256 AA:BB:CC\r\n";
        String out = NetherNetIdentity.assertion(key, null, NetherNetIdentity.DEFAULT_DOMAIN).apply(sdp);
        int identity = out.indexOf("a=identity:");
        assertTrue(identity > 0 && identity < out.indexOf("m=application"), "session-level attribute before the media section");
        String b64 = out.substring(identity + "a=identity:".length(), out.indexOf("\r\n", identity));
        var json = com.google.gson.JsonParser.parseString(new String(java.util.Base64.getDecoder().decode(b64))).getAsJsonObject();
        assertEquals("default", json.getAsJsonObject("idp").get("protocol").getAsString());
        var assertion = com.google.gson.JsonParser.parseString(json.get("assertion").getAsString()).getAsJsonObject();
        assertTrue(assertion.get("fingerprints").getAsString().contains(".."), "detached JWS");
        assertEquals(3, assertion.get("token").getAsString().split("\\.").length);
    }

    @Test
    void identityDomainComesFromTheTokenIssuer() {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"iss\":\"https://example.test/auth\"}".getBytes());
        assertEquals("https://example.test/auth/", NetherNetIdentity.domainOf("h." + payload + ".s"));
        assertEquals(NetherNetIdentity.DEFAULT_DOMAIN, NetherNetIdentity.domainOf("garbage"));
    }
}
