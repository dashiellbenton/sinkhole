package dev.sinkhole;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A tiny stand-in Bedrock server (accepts any login, serves a flat stone/grass world) used to test the proxy end to
 * end without a real server. Usage: FakeBedrockServer [port]
 */
public final class FakeBedrockServer {
    static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19132;
        var codec = BedrockCodecs.current();
        int stone = hashOf("minecraft:stone");
        int grass = hashOf("minecraft:grass_block");
        int bedrock = hashOf("minecraft:bedrock");
        int air = hashOf("minecraft:air");
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("Fake").subMotd("fake").playerCount(0).maximumPlayerCount(5)
                .gameType("Survival").protocolVersion(codec.getProtocolVersion()).version(codec.getMinecraftVersion())
                .ipv4Port(port).serverId(1);
        new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(new NioEventLoopGroup())
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    protected void initSession(BedrockServerSession session) {
                        session.setPacketHandler(new Handler(session, stone, grass, bedrock, air));
                    }
                }).bind(new InetSocketAddress("127.0.0.1", port)).sync();
        System.out.println("FakeBedrockServer on " + port + " (protocol " + codec.getProtocolVersion() + ")");
        Thread.currentThread().join();
    }

    static int hashOf(String name) throws Exception {
        try (NBTInputStream in = NbtUtils.createGZIPReader(FakeBedrockServer.class.getResourceAsStream("/data/block_palette.nbt"))) {
            NbtMap root = (NbtMap) in.readTag();
            for (NbtMap b : root.getList("blocks", NbtType.COMPOUND)) {
                if (b.getString("name").equals(name)) {
                    return b.getInt("network_id");
                }
            }
        }
        throw new IllegalStateException(name);
    }

    static final class Handler implements BedrockPacketHandler {
        final BedrockServerSession s;
        final int stone, grass, bedrock, air;
        int inputs;

        Handler(BedrockServerSession s, int stone, int grass, int bedrock, int air) {
            this.s = s;
            this.stone = stone;
            this.grass = grass;
            this.bedrock = bedrock;
            this.air = air;
        }

        @Override
        public PacketSignal handlePacket(BedrockPacket p) {
            return p.handle(this);
        }

        @Override
        public PacketSignal handle(RequestNetworkSettingsPacket p) {
            s.setCodec(BedrockCodecs.current());
            NetworkSettingsPacket n = new NetworkSettingsPacket();
            n.setCompressionThreshold(1);
            n.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
            s.sendPacketImmediately(n);
            s.setCompression(PacketCompressionAlgorithm.ZLIB);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(LoginPacket p) {
            System.out.println("[fake] login ok");
            PlayStatusPacket ok = new PlayStatusPacket();
            ok.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
            s.sendPacket(ok);
            ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
            info.setWorldTemplateId(new UUID(0, 0));
            info.setWorldTemplateVersion("");
            s.sendPacket(info);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackClientResponsePacket p) {
            if (p.getStatus() == ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS) {
                ResourcePackStackPacket st = new ResourcePackStackPacket();
                st.setGameVersion(BedrockCodecs.current().getMinecraftVersion());
                s.sendPacket(st);
            } else if (p.getStatus() == ResourcePackClientResponsePacket.Status.COMPLETED) {
                startGame();
            }
            return PacketSignal.HANDLED;
        }

        void startGame() {
            StartGamePacket sg = new StartGamePacket();
            sg.setUniqueEntityId(1);
            sg.setRuntimeEntityId(1);
            sg.setPlayerGameType(GameType.SURVIVAL);
            sg.setPlayerPosition(Vector3f.from(8, -52 + 1.62f, 8));
            sg.setRotation(Vector2f.ZERO);
            sg.setSeed(0);
            sg.setDimensionId(0);
            sg.setGeneratorId(1);
            sg.setLevelGameType(GameType.SURVIVAL);
            sg.setDifficulty(1);
            sg.setDefaultSpawn(Vector3i.from(8, -52, 8));
            sg.setLevelId("fake");
            sg.setLevelName("fake");
            sg.setPremiumWorldTemplateId("");
            sg.setMultiplayerCorrelationId("");
            sg.setVanillaVersion(BedrockCodecs.current().getMinecraftVersion());
            sg.setServerEngine("");
            sg.setBlockNetworkIdsHashed(true);
            sg.setBlockPalette(new org.cloudburstmc.nbt.NbtList<>(NbtType.COMPOUND, new ArrayList<NbtMap>()));
            var defs = ItemDefinitions.load();
            s.getPeer().getCodecHelper().setItemDefinitions(org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
                    .<org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition>builder().addAll(defs).build());
            s.getPeer().getCodecHelper().setBlockDefinitions(new org.cloudburstmc.protocol.common.DefinitionRegistry<org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition>() {
                public org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition getDefinition(int id) {
                    return () -> id;
                }

                public boolean isRegistered(org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition d) {
                    return true;
                }
            });
            sg.setPlayerPropertyData(NbtMap.EMPTY);
            sg.setWorldId("");
            sg.setScenarioId("");
            sg.setOwnerId("");
            sg.setServerId("");
            sg.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
            sg.setCustomBiomeName("");
            sg.setEducationProductionId("");
            sg.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER);
            sg.setDefaultPlayerPermission(PlayerPermission.MEMBER);
            sg.setXblBroadcastMode(GamePublishSetting.PUBLIC);
            sg.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
            sg.setForceExperimentalGameplay(org.cloudburstmc.protocol.common.util.OptionalBoolean.empty());
            sg.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
            sg.setWorldTemplateId(UUID.randomUUID());
            sg.setServerChunkTickRange(4);
            s.sendPacket(sg);
            ItemComponentPacket ic = new ItemComponentPacket();
            ic.getItems().addAll(defs);
            s.sendPacket(ic);
        }

        @Override
        public PacketSignal handle(RequestChunkRadiusPacket p) {
            int r = Math.min(p.getRadius(), 4);
            ChunkRadiusUpdatedPacket u = new ChunkRadiusUpdatedPacket();
            u.setRadius(r);
            s.sendPacket(u);
            NetworkChunkPublisherUpdatePacket pub = new NetworkChunkPublisherUpdatePacket();
            pub.setPosition(Vector3i.from(8, -52, 8));
            pub.setRadius(r * 16);
            s.sendPacket(pub);
            for (int cx = -r; cx <= r; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    LevelChunkPacket lc = new LevelChunkPacket();
                    lc.setChunkX(cx);
                    lc.setChunkZ(cz);
                    lc.setDimension(0);
                    lc.setCachingEnabled(false);
                    if (System.getenv("FAKE_SUBCHUNKS") != null) {
                        lc.setRequestSubChunks(true);
                        lc.setSubChunksLength(-1);
                        ByteBuf biomes = Unpooled.buffer();
                        writeBiomes(biomes);
                        lc.setData(biomes);
                    } else {
                        lc.setSubChunksLength(24);
                        lc.setData(chunkData());
                    }
                    s.sendPacket(lc);
                }
            }
            PlayStatusPacket spawn = new PlayStatusPacket();
            spawn.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
            s.sendPacket(spawn);
            return PacketSignal.HANDLED;
        }

        /** y=-64 bedrock, -63..-54 stone, -53 grass (world y), everything else air. */
        ByteBuf chunkData() {
            ByteBuf out = Unpooled.buffer();
            for (int sub = 0; sub < 24; sub++) {
                writeSub(out, sub);
            }
            writeBiomes(out);
            return out;
        }

        void writeBiomes(ByteBuf out) {
            writeStorage(out, new int[4096], List.of(1));
            for (int i = 1; i < 24; i++) {
                out.writeByte(0xFF);
            }
            out.writeByte(0);
        }

        void writeSub(ByteBuf out, int sub) {
            {
                out.writeByte(9);
                out.writeByte(1);
                out.writeByte(sub - 4);
                int[] idx = new int[4096];
                List<Integer> pal = new ArrayList<>();
                if (sub == 0) {
                    pal.add(bedrock);
                    pal.add(stone);
                    pal.add(grass);
                    pal.add(air);
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 16; y++) {
                                int worldY = -64 + y;
                                idx[(x << 8) | (z << 4) | y] = worldY == -64 ? 0 : worldY < -53 ? 1 : worldY == -53 ? 2 : 3;
                            }
                        }
                    }
                } else {
                    pal.add(air);
                }
                writeStorage(out, idx, pal);
            }
        }

        @Override
        public PacketSignal handle(SubChunkRequestPacket p) {
            SubChunkPacket r = new SubChunkPacket();
            r.setDimension(0);
            r.setCenterPosition(p.getSubChunkPosition());
            for (Vector3i off : p.getPositionOffsets()) {
                SubChunkData d = new SubChunkData();
                d.setPosition(off);
                d.setResult(SubChunkRequestResult.SUCCESS);
                ByteBuf b = Unpooled.buffer();
                writeSub(b, off.getY());
                d.setData(b);
                d.setHeightMapType(HeightMapDataType.NO_DATA);
                d.setRenderHeightMapType(HeightMapDataType.NO_DATA);
                r.getSubChunks().add(d);
            }
            s.sendPacket(r);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(SetLocalPlayerAsInitializedPacket p) {
            System.out.println("[fake] player initialized");
            TextPacket t = new TextPacket();
            t.setType(TextPacket.Type.RAW);
            t.setNeedsTranslation(false);
            t.setSourceName("");
            t.setXuid("");
            t.setPlatformChatId("");
            t.setMessage("Welcome BedrockGuy! (from the fake server)");
            s.sendPacket(t);

            UpdateAttributesPacket ua = new UpdateAttributesPacket();
            ua.setRuntimeEntityId(1);
            ua.setAttributes(List.of(new AttributeData("minecraft:health", 0, 20, 14), new AttributeData("minecraft:player.hunger", 0, 20, 17)));
            s.sendPacket(ua);

            var helper = s.getPeer().getCodecHelper();
            InventoryContentPacket inv = new InventoryContentPacket();
            inv.setContainerId(0);
            List<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> items = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                items.add(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR);
            }
            items.set(0, org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.builder()
                    .definition(ItemDefinitions.load().stream().filter(d -> d.getIdentifier().equals("minecraft:diamond_sword")).findFirst().orElseThrow()).count(1).build());
            inv.setContents(items);
            inv.setContainerNameData(new org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName(
                    org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.INVENTORY, null));
            s.sendPacket(inv);

            AddEntityPacket z = new AddEntityPacket();
            z.setUniqueEntityId(77);
            z.setRuntimeEntityId(77);
            z.setIdentifier("minecraft:zombie");
            z.setPosition(Vector3f.from(11, -52, 8));
            z.setMotion(Vector3f.ZERO);
            z.setRotation(Vector2f.ZERO);
            s.sendPacket(z);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(InventoryTransactionPacket p) {
            System.out.println("[fake] transaction " + p.getTransactionType() + " action=" + p.getActionType() + " entity=" + p.getRuntimeEntityId());
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayerAuthInputPacket p) {
            if (!p.getPlayerActions().isEmpty() || p.getItemUseTransaction() != null) {
                System.out.println("[fake] actions=" + p.getPlayerActions() + " itemUse=" + (p.getItemUseTransaction() != null));
            }
            if (++inputs % 40 == 1) {
                System.out.println("[fake] input #" + inputs + " pos=" + p.getPosition());
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(TextPacket p) {
            System.out.println("[fake] chat from " + p.getSourceName() + ": " + p.getMessage());
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(CommandRequestPacket p) {
            System.out.println("[fake] command: " + p.getCommand());
            return PacketSignal.HANDLED;
        }
    }

    static void writeStorage(ByteBuf out, int[] indices, List<Integer> palette) {
        int needed = 32 - Integer.numberOfLeadingZeros(Math.max(palette.size() - 1, 0));
        int bits = 1;
        for (int w : WIDTHS) {
            bits = w;
            if (w >= needed) {
                break;
            }
        }
        out.writeByte((bits << 1) | 1);
        int perWord = 32 / bits;
        int words = (4096 + perWord - 1) / perWord;
        for (int w = 0; w < words; w++) {
            int word = 0;
            for (int j = 0; j < perWord; j++) {
                int i = w * perWord + j;
                if (i >= 4096) {
                    break;
                }
                word |= indices[i] << (j * bits);
            }
            out.writeIntLE(word);
        }
        writeVarInt(out, (palette.size() << 1) ^ (palette.size() >> 31));
        for (int rid : palette) {
            writeVarInt(out, (rid << 1) ^ (rid >> 31));
        }
    }

    static void writeVarInt(ByteBuf out, int v) {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }
}
