package dev.sinkhole;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * One Bedrock client <-> one Java server connection.
 *
 * The Bedrock side is driven by this class as a BedrockPacketHandler; the Java side is an
 * MCProtocolLib client logged in with the Microsoft account from {@link AuthService}.
 */
public final class ProxySession implements BedrockPacketHandler {
    private final SinkholeConfig config;
    private final AuthService auth;
    private final BedrockServerSession bedrock;
    private final ChunkTranslator chunks;

    private volatile ClientSession java;
    private NameRewriter names;
    private boolean javaStarted;
    private volatile boolean closed;

    // Last position the Java server told us about; Bedrock input is relative to it.
    private volatile Vector3f position = Vector3f.ZERO;

    public ProxySession(SinkholeConfig config, AuthService auth, BedrockServerSession bedrock, ChunkTranslator chunks) {
        this.chunks = chunks;
        this.config = config;
        this.auth = auth;
        this.bedrock = bedrock;
    }

    public void close() {
        closed = true;
        ClientSession s = java;
        if (s != null && s.isConnected()) {
            s.disconnect("Bedrock client disconnected");
        }
    }

    // ---------------------------------------------------------------- Bedrock login handshake

    private static final boolean DEBUG = System.getenv("SINKHOLE_DEBUG") != null;

    private static void debug(String msg) {
        if (DEBUG) {
            System.out.println("[debug] " + msg);
        }
    }

    @Override
    public PacketSignal handlePacket(org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet) {
        debug("bedrock -> proxy: " + packet.getClass().getSimpleName());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        debug("bedrock disconnected: " + reason);
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        if (packet.getProtocolVersion() != Bedrock_v2193.CODEC.getProtocolVersion()) {
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(packet.getProtocolVersion() > Bedrock_v2193.CODEC.getProtocolVersion()
                    ? PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD
                    : PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            bedrock.sendPacketImmediately(status);
            bedrock.disconnect("Unsupported Bedrock version. SinkholeMC speaks " + Bedrock_v2193.CODEC.getMinecraftVersion());
            return PacketSignal.HANDLED;
        }
        bedrock.setCodec(Bedrock_v2193.CODEC);

        NetworkSettingsPacket settings = new NetworkSettingsPacket();
        settings.setCompressionThreshold(1);
        settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        bedrock.sendPacketImmediately(settings);
        bedrock.setCompression(PacketCompressionAlgorithm.ZLIB);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        // The Bedrock identity is never forwarded; we only learn its name so it can be swapped for the Java one.
        names = new NameRewriter(extractGamertag(packet), auth.javaName());

        PlayStatusPacket ok = new PlayStatusPacket();
        ok.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        bedrock.sendPacket(ok);

        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.setForcedToAccept(false);
        info.setWorldTemplateId(new UUID(0, 0));
        info.setWorldTemplateVersion("");
        bedrock.sendPacket(info);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setForcedToAccept(false);
                stack.setGameVersion(Bedrock_v2193.CODEC.getMinecraftVersion());
                bedrock.sendPacket(stack);
            }
            case COMPLETED -> connectJava();
            default -> bedrock.disconnect("Resource packs are not supported");
        }
        return PacketSignal.HANDLED;
    }

    private String extractGamertag(LoginPacket packet) {
        try {
            String jwt = null;
            if (packet.getAuthPayload() instanceof CertificateChainPayload chain) {
                List<String> certs = chain.getChain();
                for (String c : certs) {
                    JsonObject extra = payload(c).getAsJsonObject("extraData");
                    if (extra != null && extra.has("displayName")) {
                        return extra.get("displayName").getAsString();
                    }
                }
            } else if (packet.getAuthPayload() instanceof TokenPayload token) {
                jwt = token.getToken();
            }
            if (jwt != null) {
                JsonObject p = payload(jwt);
                for (String key : new String[]{"xname", "displayName", "name"}) {
                    if (p.has(key)) {
                        return p.get(key).getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JsonObject payload(String jwt) {
        String body = jwt.split("\\.")[1];
        return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body))).getAsJsonObject();
    }

    // ---------------------------------------------------------------- Java connection

    private void connectJava() {
        if (javaStarted) {
            return;
        }
        javaStarted = true;
        try {
            GameProfile profile = new GameProfile(auth.javaUuid(), auth.javaName());
            MinecraftProtocol protocol = auth.isOffline()
                    ? new MinecraftProtocol(auth.javaName())
                    : new MinecraftProtocol(profile, auth.accessToken());
            ClientSession session = ClientNetworkSessionFactory.factory()
                    .setRemoteSocketAddress(new java.net.InetSocketAddress(config.serverHost(), config.serverPort()))
                    .setProtocol(protocol)
                    .create();
            session.addListener(new SessionAdapter() {
                @Override
                public void packetReceived(Session s, Packet p) {
                    onJavaPacket(p);
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    if (!closed) {
                        bedrock.disconnect(PlainTextComponentSerializer.plainText().serialize(event.getReason()));
                    }
                }
            });
            java = session;
            session.connect();
        } catch (Exception e) {
            bedrock.disconnect("Could not connect to " + config.server + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Java -> Bedrock

    private void onJavaPacket(Packet p) {
        if (p instanceof ClientboundLoginPacket login) {
            sendStartGame(login);
        } else if (p instanceof ClientboundPlayerPositionPacket pos) {
            position = Vector3f.from(pos.getPosition().getX(), pos.getPosition().getY(), pos.getPosition().getZ());
            MovePlayerPacket move = new MovePlayerPacket();
            move.setRuntimeEntityId(1);
            move.setPosition(position.add(0, 1.62f, 0));
            move.setRotation(Vector3f.from(pos.getXRot(), pos.getYRot(), pos.getYRot()));
            move.setMode(MovePlayerPacket.Mode.TELEPORT);
            move.setOnGround(false);
            bedrock.sendPacket(move);
        } else if (p instanceof ClientboundSystemChatPacket chat) {
            sendText(chat.getContent(), null, chat.isOverlay() ? TextPacket.Type.TIP : TextPacket.Type.RAW);
        } else if (p instanceof ClientboundPlayerChatPacket chat) {
            Component body = chat.getUnsignedContent() != null ? chat.getUnsignedContent() : Component.text(chat.getContent());
            sendText(Component.text("<").append(chat.getName()).append(Component.text("> ")).append(body), null, TextPacket.Type.RAW);
        }
        else if (p instanceof ClientboundLevelChunkWithLightPacket chunk) {
            sendChunk(chunk);
        }
        // TODO(world): blocks updates, entities, inventory, effects, combat - see README "Status".
    }

    private void sendChunk(ClientboundLevelChunkWithLightPacket chunk) {
        // Sections per column: the overworld is 24 (-64..320); other dimensions are not handled yet.
        ChunkTranslator.Result r = chunks.translate(chunk.getChunkData(), 24);
        LevelChunkPacket out = new LevelChunkPacket();
        out.setChunkX(chunk.getX());
        out.setChunkZ(chunk.getZ());
        out.setDimension(0);
        out.setSubChunksLength(r.subChunkCount);
        out.setCachingEnabled(false);
        out.setData(io.netty.buffer.Unpooled.wrappedBuffer(r.data));
        bedrock.sendPacket(out);
    }

    private void sendText(Component component, String source, TextPacket.Type type) {
        TextPacket text = new TextPacket();
        text.setType(type);
        text.setNeedsTranslation(false);
        text.setSourceName(source == null ? "" : source);
        text.setXuid("");
        text.setPlatformChatId("");
        text.setMessage(LegacyComponentSerializer.legacySection().serialize(component));
        bedrock.sendPacket(text);
    }

    private void sendStartGame(ClientboundLoginPacket login) {
        StartGamePacket start = new StartGamePacket();
        start.setUniqueEntityId(1);
        start.setRuntimeEntityId(1);
        start.setPlayerGameType(GameType.SURVIVAL);
        start.setPlayerPosition(Vector3f.from(0, 100, 0));
        start.setRotation(Vector2f.ZERO);
        start.setSeed(0);
        start.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
        start.setCustomBiomeName("");
        start.setDimensionId(0);
        start.setGeneratorId(1);
        start.setLevelGameType(GameType.SURVIVAL);
        start.setDifficulty(2);
        start.setDefaultSpawn(Vector3i.from(0, 100, 0));
        start.setAchievementsDisabled(true);
        start.setCommandsEnabled(true);
        start.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        start.setServerChunkTickRange(4);
        start.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        start.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        start.setVanillaVersion(Bedrock_v2193.CODEC.getMinecraftVersion());
        start.setLevelId("sinkhole");
        start.setLevelName("SinkholeMC");
        start.setPremiumWorldTemplateId("");
        start.setMultiplayerCorrelationId("");
        start.setServerEngine("");
        start.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND, new ArrayList<NbtMap>()));
        start.setItemDefinitions(ItemDefinitions.load());
        start.setPlayerPropertyData(NbtMap.EMPTY);
        start.setWorldId("");
        start.setScenarioId("");
        start.setOwnerId("");
        start.setServerId("");
        bedrock.sendPacket(start);

        ChunkRadiusUpdatedPacket radius = new ChunkRadiusUpdatedPacket();
        radius.setRadius(Math.max(2, login.getViewDistance()));
        bedrock.sendPacket(radius);

        NetworkChunkPublisherUpdatePacket publisher = new NetworkChunkPublisherUpdatePacket();
        publisher.setPosition(Vector3i.from(0, 100, 0));
        publisher.setRadius(Math.max(2, login.getViewDistance()) * 16);
        bedrock.sendPacket(publisher);

        PlayStatusPacket spawn = new PlayStatusPacket();
        spawn.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        bedrock.sendPacket(spawn);
    }

    // ---------------------------------------------------------------- Bedrock -> Java

    @Override
    public PacketSignal handle(TextPacket packet) {
        ClientSession s = java;
        if (s == null || packet.getType() != TextPacket.Type.CHAT) {
            return PacketSignal.HANDLED;
        }
        // Whatever the Bedrock gamertag appears as in the message becomes the Java name.
        String message = names.toJava(packet.getMessage());
        if (message.startsWith("/")) {
            s.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket(message.substring(1)));
        } else {
            s.send(new ServerboundChatPacket(message, System.currentTimeMillis(), 0L, null, 0, new java.util.BitSet(), 0));
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayerAuthInputPacket packet) {
        ClientSession s = java;
        if (s == null) {
            return PacketSignal.HANDLED;
        }
        Vector3f pos = packet.getPosition();
        Vector3f rot = packet.getRotation();
        // Bedrock reports eye position; Java wants feet.
        s.send(new ServerboundMovePlayerPosRotPacket(true, false, pos.getX(), pos.getY() - 1.62, pos.getZ(), rot.getY(), rot.getX()));
        return PacketSignal.HANDLED;
    }
}
