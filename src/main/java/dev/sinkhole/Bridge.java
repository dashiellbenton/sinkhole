package dev.sinkhole;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One player: the Java client (MCProtocolLib server session) on one side, the Bedrock server (via
 * {@link BedrockUpstream}) on the other, with the packet translation in between.
 */
public final class Bridge implements BedrockUpstream.Listener {
    private static final boolean DEBUG = System.getenv("SINKHOLE_DEBUG") != null;
    private static final float EYE_HEIGHT = 1.62f;

    private final SinkholeConfig config;
    private final AuthService auth;
    private final Registries registries;
    private final BedrockChunks chunks;
    private final Session client;
    private final String javaName;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sinkhole-tick");
        t.setDaemon(true);
        return t;
    });

    private BedrockUpstream upstream;
    private Runnable onClosed = () -> { };
    private volatile boolean closed;

    // From the Bedrock StartGame
    private volatile StartGamePacket start;
    private volatile boolean javaPlaying;
    private volatile int sections = 24;

    // Latest Java-side movement, sent to Bedrock every tick
    private volatile double x, y, z;
    private volatile float yaw, pitch;
    private volatile boolean onGround;
    private double lastX, lastY, lastZ;
    private long tick;
    private volatile boolean spawned;
    private boolean sentInitialized;

    private String gamertag;

    public Bridge(SinkholeConfig config, AuthService auth, Registries registries, BedrockChunks chunks, Session client) {
        this.config = config;
        this.auth = auth;
        this.registries = registries;
        this.chunks = chunks;
        this.client = client;
        this.javaName = client.getFlag(MinecraftConstants.PROFILE_KEY).getName();
    }

    public void onClosed(Runnable r) {
        this.onClosed = r;
    }

    public void start() {
        System.out.println("[Sinkhole] " + javaName + " connected, joining Bedrock server " + config.server + " ...");
        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet p) {
                onJavaPacket(p);
            }

            @Override
            public void packetError(org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent event) {
                System.err.println("[Sinkhole] Java packet error: " + event.getCause());
                if (DEBUG) {
                    event.getCause().printStackTrace();
                }
            }

            @Override
            public void packetSent(Session s, Packet p) {
                if (DEBUG) {
                    System.out.println("[debug] proxy -> java client: " + p.getClass().getSimpleName());
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                close();
            }
        });
        try {
            AuthService.Identity id = auth.identity();
            gamertag = id.gamertag();
            upstream = new BedrockUpstream(id, config.serverHost(), config.serverPort(), this);
            upstream.connect();
        } catch (Exception e) {
            client.disconnect("Could not log in to Bedrock: " + e.getMessage());
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ticker.shutdownNow();
        if (upstream != null) {
            upstream.close();
        }
        if (client.isConnected()) {
            client.disconnect("SinkholeMC: connection closed");
        }
        onClosed.run();
    }

    @Override
    public void onDisconnect(String reason) {
        System.out.println("[Sinkhole] Bedrock server disconnected: " + reason);
        if (!closed && client.isConnected()) {
            client.disconnect(reason);
        }
        close();
    }

    // ---------------------------------------------------------------- Bedrock -> Java

    @Override
    public void onPacket(BedrockPacket p) {
        try {
            handleBedrock(p);
        } catch (Exception e) {
            System.err.println("[Sinkhole] Error translating " + p.getClass().getSimpleName() + ": " + e);
            if (DEBUG) {
                e.printStackTrace();
            }
        }
    }

    private void handleBedrock(BedrockPacket p) {
        if (p instanceof StartGamePacket sg) {
            start = sg;
            chunks.setHashed(sg.isBlockNetworkIdsHashed());
            sections = switch (sg.getDimensionId()) {
                case 1, 2 -> 16;
                default -> 24;
            };
            Vector3f pos = sg.getPlayerPosition();
            x = pos.getX();
            y = pos.getY() - EYE_HEIGHT;
            z = pos.getZ();
            upstream.send(clientCache());
            RequestChunkRadiusPacket r = new RequestChunkRadiusPacket();
            r.setRadius(8);
            r.setMaxRadius(8);
            upstream.send(r);
            startReceived = true;
            maybeBeginJavaPlay();
        } else if (p instanceof PlayStatusPacket ps) {
            if (ps.getStatus() == PlayStatusPacket.Status.PLAYER_SPAWN && !sentInitialized) {
                sentInitialized = true;
                SetLocalPlayerAsInitializedPacket init = new SetLocalPlayerAsInitializedPacket();
                init.setRuntimeEntityId(start.getRuntimeEntityId());
                upstream.send(init);
                spawned = true;
                ticker.scheduleAtFixedRate(this::sendInput, 0, 50, TimeUnit.MILLISECONDS);
            }
        } else if (p instanceof NetworkChunkPublisherUpdatePacket pub) {
            if (javaPlaying) {
                Vector3i c = pub.getPosition();
                client.send(new ClientboundSetChunkCacheCenterPacket(c.getX() >> 4, c.getZ() >> 4));
            }
        } else if (p instanceof LevelChunkPacket lc) {
            if (lc.isRequestSubChunks() || lc.getSubChunksLength() < 0) {
                return; // sub-chunk request mode is not handled yet
            }
            client.send(chunks.translate(lc.getChunkX(), lc.getChunkZ(), lc.getData(), lc.getSubChunksLength(), sections, start.isBlockNetworkIdsHashed(), 0));
        } else if (p instanceof UpdateBlockPacket ub) {
            if (ub.getDataLayer() == 0 && javaPlaying) {
                int rid = ub.getDefinition().getRuntimeId();
                int state = chunks.blocks().toJava(rid, start.isBlockNetworkIdsHashed());
                client.send(new ClientboundBlockUpdatePacket(new BlockChangeEntry(ub.getBlockPosition(), state)));
            }
        } else if (p instanceof MovePlayerPacket mp) {
            if (start != null && mp.getRuntimeEntityId() == start.getRuntimeEntityId() && javaPlaying) {
                teleport(mp.getPosition().getX(), mp.getPosition().getY() - EYE_HEIGHT, mp.getPosition().getZ(), mp.getRotation().getY(), mp.getRotation().getX());
            }
        } else if (p instanceof TextPacket t) {
            if (DEBUG) {
                System.out.println("[debug] text: type=" + t.getType() + " msg=" + t.getMessage() + " playing=" + javaPlaying);
            }
            if (javaPlaying) {
                String msg = t.getMessage();
                String who = t.getSourceName();
                String line = (t.getType() == TextPacket.Type.CHAT && who != null && !who.isEmpty())
                        ? "<" + who + "> " + msg : msg;
                client.send(new ClientboundSystemChatPacket(Component.text(rename(line, gamertag, javaName)), false));
            }
        } else if (p instanceof DisconnectPacket d) {
            String msg = d.isMessageSkipped() || d.getKickMessage() == null || d.getKickMessage().isEmpty()
                    ? "Disconnected by the Bedrock server (" + d.getReason() + ")" : d.getKickMessage();
            System.out.println("[Sinkhole] Bedrock server kicked us: " + msg);
            client.disconnect(msg);
        } else if (p instanceof SetTimePacket st) {
            // TODO: translate to the Java day cycle clock
        }
    }

    private static ClientCacheStatusPacket clientCache() {
        ClientCacheStatusPacket c = new ClientCacheStatusPacket();
        c.setSupported(false);
        return c;
    }

    private volatile boolean startReceived;
    // MCProtocolLib calls our login handler once the Java client has finished configuration (it sends the registries itself).
    private volatile boolean javaConfigured = true;
    private boolean playBegun;

    /** The Java client enters play once it has finished configuration AND the Bedrock server has sent StartGame. */
    private synchronized void maybeBeginJavaPlay() {
        if (startReceived && javaConfigured && !playBegun) {
            playBegun = true;
            beginJavaPlay();
        }
    }

    private void beginJavaPlay() {
        StartGamePacket sg = start;
        String dimensionName = switch (sg.getDimensionId()) {
            case 1 -> "minecraft:the_nether";
            case 2 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        GameMode mode = switch (sg.getPlayerGameType()) {
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SURVIVAL_VIEWER, CREATIVE_VIEWER -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        PlayerSpawnInfo spawn = new PlayerSpawnInfo(registries.idOf("minecraft:dimension_type", dimensionName), Key.key(dimensionName),
                0L, mode, null, false, false, null, 0, 63);
        client.send(new ClientboundLoginPacket(sg.getRuntimeEntityId() > Integer.MAX_VALUE ? 1 : (int) sg.getRuntimeEntityId(), false,
                new Key[]{Key.key(dimensionName)}, 20, 8, 8, false, true, false, spawn, false, false));
        javaPlaying = true;
        client.send(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
        Vector3f pos = sg.getPlayerPosition();
        client.send(new ClientboundSetChunkCacheCenterPacket((int) Math.floor(pos.getX()) >> 4, (int) Math.floor(pos.getZ()) >> 4));
        teleport(pos.getX(), pos.getY() - EYE_HEIGHT, pos.getZ(), sg.getRotation().getY(), sg.getRotation().getX());
    }

    private int teleportId = 1;

    private void teleport(double nx, double ny, double nz, float nyaw, float npitch) {
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = npitch;
        client.send(new ClientboundPlayerPositionPacket(teleportId++, nx, ny, nz, 0, 0, 0, nyaw, npitch));
    }

    // ---------------------------------------------------------------- Java -> Bedrock

    private void onJavaPacket(Packet p) {
        if (DEBUG) {
            System.out.println("[debug] java client -> proxy: " + p.getClass().getSimpleName());
        }
        if (p instanceof ServerboundFinishConfigurationPacket) {
            javaConfigured = true;
            maybeBeginJavaPlay();
        } else if (p instanceof ServerboundMovePlayerPosPacket m) {
            move(m.getX(), m.getY(), m.getZ(), yaw, pitch, m.isOnGround());
        } else if (p instanceof ServerboundMovePlayerPosRotPacket m) {
            move(m.getX(), m.getY(), m.getZ(), m.getYaw(), m.getPitch(), m.isOnGround());
        } else if (p instanceof ServerboundMovePlayerRotPacket m) {
            move(x, y, z, m.getYaw(), m.getPitch(), m.isOnGround());
        } else if (p instanceof ServerboundMovePlayerStatusOnlyPacket m) {
            onGround = m.isOnGround();
        } else if (p instanceof ServerboundChatPacket c) {
            sendChat(c.getMessage());
        } else if (p instanceof ServerboundChatCommandPacket c) {
            sendCommand("/" + c.getCommand());
        }
    }

    private void move(double nx, double ny, double nz, float nyaw, float npitch, boolean ground) {
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = npitch;
        onGround = ground;
    }

    /** Bedrock servers expect an input packet every tick. */
    private void sendInput() {
        if (closed || !spawned) {
            return;
        }
        PlayerAuthInputPacket in = new PlayerAuthInputPacket();
        in.setTick(tick++);
        in.setInputMode(InputMode.MOUSE);
        in.setPlayMode(ClientPlayMode.NORMAL);
        in.setInputInteractionModel(InputInteractionModel.CLASSIC);
        in.setCameraOrientation(Vector3f.from(0, 0, 0));
        in.setPosition(Vector3f.from(x, y + EYE_HEIGHT, z));
        in.setRotation(Vector3f.from(pitch, yaw, yaw));
        in.setDelta(Vector3f.from(x - lastX, y - lastY, z - lastZ));
        in.setMotion(Vector2f.ZERO);
        in.setRawMoveVector(Vector2f.ZERO);
        in.setAnalogMoveVector(Vector2f.ZERO);
        in.setInteractRotation(Vector2f.from(pitch, yaw));
        Set<PlayerAuthInputData> flags = new HashSet<>();
        if (Math.abs(x - lastX) > 0.001 || Math.abs(z - lastZ) > 0.001) {
            flags.add(PlayerAuthInputData.UP);
        }
        in.getInputData().addAll(flags);
        lastX = x;
        lastY = y;
        lastZ = z;
        upstream.send(in);
    }

    private void sendChat(String message) {
        TextPacket t = new TextPacket();
        t.setType(TextPacket.Type.CHAT);
        t.setNeedsTranslation(false);
        t.setSourceName(gamertag);
        t.setXuid("");
        t.setPlatformChatId("");
        t.setMessage(rename(message, javaName, gamertag));
        upstream.send(t);
    }

    private void sendCommand(String command) {
        CommandRequestPacket c = new CommandRequestPacket();
        c.setCommand(rename(command, javaName, gamertag));
        c.setCommandOriginData(new CommandOriginData(CommandOriginType.PLAYER, java.util.UUID.randomUUID(), "", 0));
        c.setInternal(false);
        upstream.send(c);
    }

    /** Replace every whole-word occurrence of {@code from} with {@code to} (case-insensitive). */
    static String rename(String text, String from, String to) {
        if (text == null || from == null || from.isBlank() || from.equals(to)) {
            return text;
        }
        return text.replaceAll("(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(from) + "(?![A-Za-z0-9_])",
                java.util.regex.Matcher.quoteReplacement(to));
    }
}
