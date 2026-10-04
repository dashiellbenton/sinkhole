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
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.data.inventory.HandSlot;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.ClockNetworkState;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RainStrengthValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.ThunderStrengthValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerAbilitiesPacket;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final GameData gameData;
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
    private String xuid = "";
    private Entities entities;
    private Inventory inventory;
    private Containers containers;

    // Player state mirrored from Bedrock attributes
    private float health = 20, hunger = 20, saturation = 5, xpProgress, xpLevel;
    private int selfJavaId = 1;
    private boolean startFlying, stopFlying;
    private final List<PlayerBlockActionData> pendingActions = new ArrayList<>();
    private boolean sneaking, sprinting, jumping, forward, backward, left, right;

    public Bridge(SinkholeConfig config, AuthService auth, Registries registries, BedrockChunks chunks, GameData gameData, Session client) {
        this.gameData = gameData;
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
            xuid = id.xuid() == null ? "" : id.xuid();
            entities = new Entities(client, gamertag, javaName);
            inventory = new Inventory(gameData);
            containers = new Containers(inventory, upstream_ -> upstream.send(upstream_), client::send, () -> { });
            upstream = new BedrockUpstream(id, config.serverHost(), config.serverPort(), config.transport, this);
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
                requestSubChunks(lc);
                return;
            }
            client.send(chunks.translate(lc.getChunkX(), lc.getChunkZ(), lc.getData(), lc.getSubChunksLength(), sections, start.isBlockNetworkIdsHashed(), 0));
        } else if (p instanceof SubChunkPacket sc) {
            receiveSubChunks(sc);
        } else if (p instanceof UpdateBlockPacket ub) {
            if (ub.getDataLayer() == 0 && javaPlaying) {
                int rid = ub.getDefinition().getRuntimeId();
                int state = chunks.blocks().toJava(rid, start.isBlockNetworkIdsHashed());
                client.send(new ClientboundBlockUpdatePacket(new BlockChangeEntry(ub.getBlockPosition(), state)));
            }
        } else if (p instanceof MovePlayerPacket mp) {
            if (start != null && mp.getRuntimeEntityId() != start.getRuntimeEntityId()) {
                entities.move(mp);
            } else if (start != null && javaPlaying) {
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
        } else if (p instanceof AddPlayerPacket ap) {
            entities.addPlayer(ap);
        } else if (p instanceof AddEntityPacket ae) {
            entities.addEntity(ae);
        } else if (p instanceof AddItemEntityPacket ai) {
            entities.addItem(ai, inventory);
        } else if (p instanceof SetEntityDataPacket sd) {
            entities.metadata(sd.getRuntimeEntityId(), sd.getMetadata());
        } else if (p instanceof RemoveEntityPacket re) {
            entities.remove(re);
        } else if (p instanceof MoveEntityAbsolutePacket me) {
            entities.move(me);
        } else if (p instanceof MoveEntityDeltaPacket md) {
            entities.move(md);
        } else if (p instanceof UpdateAttributesPacket ua) {
            if (start != null && ua.getRuntimeEntityId() == start.getRuntimeEntityId()) {
                updateAttributes(ua);
            }
        } else if (p instanceof InventoryContentPacket ic) {
            if (DEBUG) {
                System.out.println("[debug] inventory content container=" + ic.getContainerId() + " items=" + ic.getContents().size());
            }
            if (!containers.content(ic)) {
                inventory.content(ic);
                if (javaPlaying) {
                    if (containers.isOpen()) {
                        containers.sendContent();
                    } else {
                        client.send(inventory.javaContent());
                    }
                }
            }
        } else if (p instanceof InventorySlotPacket is) {
            if (!containers.slot(is)) {
                inventory.slot(is);
                if (javaPlaying) {
                    if (containers.isOpen()) {
                        containers.sendContent();
                    } else {
                        client.send(inventory.javaContent());
                    }
                }
            }
        } else if (p instanceof ContainerOpenPacket co) {
            containers.open(co);
        } else if (p instanceof ContainerClosePacket cc) {
            containers.closedByServer(cc);
        } else if (p instanceof ItemStackResponsePacket ir) {
            containers.response(ir);
        } else if (p instanceof SetTimePacket st) {
            if (javaPlaying) {
                int clock = registries.idOf("minecraft:world_clock", "minecraft:overworld");
                client.send(new ClientboundSetTimePacket(st.getTime(), java.util.Map.of(clock, new ClockNetworkState(st.getTime(), 0f, 1f))));
            }
        } else if (p instanceof SetEntityMotionPacket sm) {
            if (start != null && sm.getRuntimeEntityId() == start.getRuntimeEntityId() && javaPlaying) {
                Vector3f m = sm.getMotion();
                client.send(new ClientboundSetEntityMotionPacket(selfJavaId, Vector3d.from(m.getX(), m.getY(), m.getZ())));
            }
        } else if (p instanceof EntityEventPacket ee) {
            entities.event(ee, start == null ? -1 : start.getRuntimeEntityId(), selfJavaId);
        } else if (p instanceof UpdatePlayerGameTypePacket gt) {
            if (start != null && gt.getEntityId() == start.getUniqueEntityId() && javaPlaying) {
                client.send(new ClientboundGameEventPacket(GameEvent.CHANGE_GAME_MODE, gameMode(gt.getGameType())));
            }
        } else if (p instanceof SetPlayerGameTypePacket gt) {
            if (javaPlaying) {
                client.send(new ClientboundGameEventPacket(GameEvent.CHANGE_GAME_MODE, switch (gt.getGamemode()) {
                    case 1 -> GameMode.CREATIVE;
                    case 2 -> GameMode.ADVENTURE;
                    case 6 -> GameMode.SPECTATOR;
                    default -> GameMode.SURVIVAL;
                }));
            }
        } else if (p instanceof UpdateAbilitiesPacket ab) {
            if (javaPlaying && start != null && ab.getUniqueEntityId() == start.getUniqueEntityId()) {
                sendAbilities(ab);
            }
        } else if (p instanceof PlayerListPacket pl) {
            entities.playerList(pl);
        } else if (p instanceof CorrectPlayerMovePredictionPacket cp) {
            if (javaPlaying) {
                teleport(cp.getPosition().getX(), cp.getPosition().getY() - EYE_HEIGHT, cp.getPosition().getZ(), yaw, pitch);
            }
        } else if (p instanceof ChangeDimensionPacket cd) {
            changeDimension(cd);
        } else if (p instanceof LevelEventPacket le) {
            weather(le);
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
        selfJavaId = sg.getRuntimeEntityId() > Integer.MAX_VALUE ? 1 : (int) sg.getRuntimeEntityId();
        client.send(new ClientboundLoginPacket(selfJavaId, false,
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
        if (start == null || inventory == null || entities == null) {
            // Not in the Bedrock world yet; only the configuration acknowledgement matters.
            if (p instanceof ServerboundFinishConfigurationPacket) {
                javaConfigured = true;
            }
            return;
        }
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
        } else if (p instanceof ServerboundSetCarriedItemPacket c) {
            selectHotbar(c.getSlot());
        } else if (p instanceof ServerboundAttackPacket a) {
            attack(a.getEntityId());
        } else if (p instanceof ServerboundPunchPacket) {
            swing();
        } else if (p instanceof ServerboundPlayerActionPacket a) {
            playerAction(a);
        } else if (p instanceof ServerboundUseItemOnPacket u) {
            useItemOn(u);
        } else if (p instanceof ServerboundPlayerInputPacket in) {
            forward = in.isForward();
            backward = in.isBackward();
            left = in.isLeft();
            right = in.isRight();
            jumping = in.isJump();
            sneaking = in.isShift();
            sprinting = in.isSprint();
        } else if (p instanceof ServerboundPlayerAbilitiesPacket ab) {
            if (ab.isFlying()) {
                startFlying = true;
            } else {
                stopFlying = true;
            }
        } else if (p instanceof ServerboundContainerClickPacket cl) {
            containers.click(cl);
        } else if (p instanceof ServerboundContainerClosePacket cl) {
            containers.closedByClient(cl.getContainerId());
        } else if (p instanceof ServerboundClientCommandPacket c) {
            if (c.getRequest() == ClientCommand.PERFORM_RESPAWN) {
                RespawnPacket r = new RespawnPacket();
                r.setRuntimeEntityId(start.getRuntimeEntityId());
                r.setState(RespawnPacket.State.CLIENT_READY);
                r.setPosition(Vector3f.from(x, y + EYE_HEIGHT, z));
                upstream.send(r);
            }
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
        if (forward) {
            flags.add(PlayerAuthInputData.UP);
        }
        if (backward) {
            flags.add(PlayerAuthInputData.DOWN);
        }
        if (left) {
            flags.add(PlayerAuthInputData.LEFT);
        }
        if (right) {
            flags.add(PlayerAuthInputData.RIGHT);
        }
        if (jumping) {
            flags.add(PlayerAuthInputData.JUMPING);
            flags.add(PlayerAuthInputData.WANT_UP);
        }
        if (sneaking) {
            flags.add(PlayerAuthInputData.SNEAKING);
        }
        if (sprinting) {
            flags.add(PlayerAuthInputData.SPRINTING);
        }
        if (startFlying) {
            flags.add(PlayerAuthInputData.START_FLYING);
            startFlying = false;
        }
        if (stopFlying) {
            flags.add(PlayerAuthInputData.STOP_FLYING);
            stopFlying = false;
        }
        synchronized (pendingActions) {
            if (!pendingActions.isEmpty()) {
                flags.add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
                in.getPlayerActions().addAll(pendingActions);
                pendingActions.clear();
            }
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
        t.setXuid(xuid);
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

    // ---------------------------------------------------------------- gameplay actions

    private static GameMode gameMode(org.cloudburstmc.protocol.bedrock.data.GameType t) {
        return switch (t) {
            case CREATIVE, CREATIVE_VIEWER -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SURVIVAL_VIEWER -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
    }

    private void sendAbilities(UpdateAbilitiesPacket ab) {
        boolean invulnerable = false, flying = false, mayFly = false, instabuild = false;
        float fly = 0.05f, walk = 0.1f;
        for (AbilityLayer layer : ab.getAbilityLayers()) {
            if (layer.getLayerType() != AbilityLayer.Type.BASE) {
                continue;
            }
            invulnerable = layer.getAbilityValues().contains(Ability.INVULNERABLE);
            flying = layer.getAbilityValues().contains(Ability.FLYING);
            mayFly = layer.getAbilityValues().contains(Ability.MAY_FLY);
            instabuild = layer.getAbilityValues().contains(Ability.INSTABUILD);
            fly = layer.getFlySpeed();
            walk = layer.getWalkSpeed();
        }
        client.send(new ClientboundPlayerAbilitiesPacket(invulnerable, mayFly, flying, instabuild, fly, walk));
    }

    private void weather(LevelEventPacket le) {
        if (!javaPlaying) {
            return;
        }
        LevelEvent type = le.getType() instanceof LevelEvent e ? e : null;
        if (type == LevelEvent.START_RAINING) {
            client.send(new ClientboundGameEventPacket(GameEvent.START_RAINING, null));
            client.send(new ClientboundGameEventPacket(GameEvent.RAIN_LEVEL_CHANGE, new RainStrengthValue(1f)));
        } else if (type == LevelEvent.STOP_RAINING) {
            client.send(new ClientboundGameEventPacket(GameEvent.STOP_RAINING, null));
        } else if (type == LevelEvent.START_THUNDERSTORM) {
            client.send(new ClientboundGameEventPacket(GameEvent.THUNDER_LEVEL_CHANGE, new ThunderStrengthValue(1f)));
        } else if (type == LevelEvent.STOP_THUNDERSTORM) {
            client.send(new ClientboundGameEventPacket(GameEvent.THUNDER_LEVEL_CHANGE, new ThunderStrengthValue(0f)));
        }
    }

    /** A chunk whose sub-chunks are requested separately (newer servers). */
    private static final class PendingChunk {
        final int x, z, total;
        final io.netty.buffer.ByteBuf biomes;
        final org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection[] sections;
        int received;

        PendingChunk(int x, int z, int total, io.netty.buffer.ByteBuf biomes, org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection[] sections) {
            this.x = x;
            this.z = z;
            this.total = total;
            this.biomes = biomes;
            this.sections = sections;
        }
    }

    private final java.util.Map<Long, PendingChunk> pendingChunks = new java.util.HashMap<>();

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void requestSubChunks(LevelChunkPacket lc) {
        int count = lc.getSubChunkLimit() > 0 ? Math.min(lc.getSubChunkLimit(), sections) : sections;
        int minIndex = currentDimension == 0 ? -4 : 0;
        pendingChunks.put(chunkKey(lc.getChunkX(), lc.getChunkZ()),
                new PendingChunk(lc.getChunkX(), lc.getChunkZ(), count, lc.getData().retainedDuplicate(), chunks.newSections(sections)));
        SubChunkRequestPacket req = new SubChunkRequestPacket();
        req.setDimension(currentDimension);
        req.setSubChunkPosition(Vector3i.from(lc.getChunkX(), minIndex, lc.getChunkZ()));
        List<Vector3i> offsets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            offsets.add(Vector3i.from(0, i, 0));
        }
        req.setPositionOffsets(offsets);
        upstream.send(req);
    }

    private void receiveSubChunks(SubChunkPacket sc) {
        for (SubChunkData d : sc.getSubChunks()) {
            Vector3i abs = sc.getCenterPosition().add(d.getPosition());
            PendingChunk pc = pendingChunks.get(chunkKey(abs.getX(), abs.getZ()));
            if (pc == null) {
                continue;
            }
            int minIndex = currentDimension == 0 ? -4 : 0;
            int index = abs.getY() - minIndex;
            if (d.getResult() == org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult.SUCCESS && d.getData() != null
                    && index >= 0 && index < pc.sections.length) {
                chunks.readSubChunk(d.getData().duplicate(), pc.sections[index]);
            }
            if (++pc.received >= pc.total) {
                pendingChunks.remove(chunkKey(pc.x, pc.z));
                chunks.applyBiomes(pc.biomes, pc.total, pc.sections, 0);
                pc.biomes.release();
                client.send(chunks.assemble(pc.x, pc.z, pc.sections));
            }
        }
    }

    private void changeDimension(ChangeDimensionPacket cd) {
        int dim = cd.getDimension();
        sections = dim == 0 ? 24 : 16;
        String name = switch (dim) {
            case 1 -> "minecraft:the_nether";
            case 2 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        entities.clear();
        currentDimension = dim;
        if (javaPlaying) {
            PlayerSpawnInfo spawn = new PlayerSpawnInfo(registries.idOf("minecraft:dimension_type", name), Key.key(name),
                    0L, gameMode(start.getPlayerGameType()), null, false, false, null, 0, 63);
            client.send(new ClientboundRespawnPacket(spawn, false, false));
            client.send(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
            Vector3f pos = cd.getPosition();
            teleport(pos.getX(), pos.getY() - EYE_HEIGHT, pos.getZ(), yaw, pitch);
        }
        // tell the Bedrock server the dimension change finished
        PlayerBlockActionData done = new PlayerBlockActionData();
        done.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
        done.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);
        queue(done);
    }

    private int currentDimension;

    private void updateAttributes(UpdateAttributesPacket ua) {
        for (AttributeData a : ua.getAttributes()) {
            switch (a.getName()) {
                case "minecraft:health" -> health = a.getValue();
                case "minecraft:player.hunger" -> hunger = a.getValue();
                case "minecraft:player.saturation" -> saturation = a.getValue();
                case "minecraft:player.experience" -> xpProgress = a.getValue();
                case "minecraft:player.level" -> xpLevel = a.getValue();
                default -> { }
            }
        }
        if (javaPlaying) {
            client.send(new ClientboundSetHealthPacket(health, (int) Math.ceil(hunger), saturation));
            client.send(new ClientboundSetExperiencePacket(xpProgress, (int) xpLevel, 0));
        }
    }

    private void selectHotbar(int slot) {
        inventory.setHeldSlot(slot);
        MobEquipmentPacket m = new MobEquipmentPacket();
        m.setRuntimeEntityId(start.getRuntimeEntityId());
        m.setItem(inventory.held());
        m.setInventorySlot(inventory.heldSlot());
        m.setHotbarSlot(inventory.heldSlot());
        m.setContainerId(0);
        upstream.send(m);
    }

    private void swing() {
        AnimatePacket a = new AnimatePacket();
        a.setRuntimeEntityId(start.getRuntimeEntityId());
        a.setAction(AnimatePacket.Action.SWING_ARM);
        upstream.send(a);
    }

    private void attack(int javaEntityId) {
        long runtime = entities.runtimeIdOf(javaEntityId);
        if (runtime < 0) {
            return;
        }
        InventoryTransactionPacket t = new InventoryTransactionPacket();
        t.setTransactionType(InventoryTransactionType.ITEM_USE_ON_ENTITY);
        t.setActionType(1); // attack
        t.setRuntimeEntityId(runtime);
        t.setHotbarSlot(inventory.heldSlot());
        t.setItemInHand(inventory.held());
        t.setPlayerPosition(Vector3f.from(x, y + EYE_HEIGHT, z));
        t.setClickPosition(Vector3f.ZERO);
        upstream.send(t);
        swing();
    }

    private void playerAction(ServerboundPlayerActionPacket a) {
        PlayerBlockActionData d = new PlayerBlockActionData();
        d.setBlockPosition(a.getPosition());
        d.setFace(a.getFace() == null ? 0 : a.getFace().ordinal());
        boolean creative = start != null && start.getPlayerGameType() == org.cloudburstmc.protocol.bedrock.data.GameType.CREATIVE;
        switch (a.getAction()) {
            case START_DIGGING -> {
                d.setAction(PlayerActionType.START_BREAK);
                queue(d);
                if (creative) {
                    PlayerBlockActionData destroy = new PlayerBlockActionData();
                    destroy.setAction(PlayerActionType.BLOCK_PREDICT_DESTROY);
                    destroy.setBlockPosition(a.getPosition());
                    destroy.setFace(d.getFace());
                    queue(destroy);
                }
            }
            case CANCEL_DIGGING -> {
                d.setAction(PlayerActionType.ABORT_BREAK);
                queue(d);
            }
            case FINISH_DIGGING -> {
                d.setAction(PlayerActionType.BLOCK_PREDICT_DESTROY);
                queue(d);
            }
            case DROP_ITEM, DROP_ITEM_STACK -> {
                d.setAction(PlayerActionType.DROP_ITEM);
                queue(d);
            }
            default -> { }
        }
    }

    private void queue(PlayerBlockActionData d) {
        synchronized (pendingActions) {
            pendingActions.add(d);
        }
    }

    private void useItemOn(ServerboundUseItemOnPacket u) {
        InventoryTransactionPacket t = new InventoryTransactionPacket();
        t.setTransactionType(InventoryTransactionType.ITEM_USE);
        t.setActionType(0); // click block
        t.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        t.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        t.setBlockPosition(u.getPosition());
        t.setBlockFace(u.getFace().ordinal());
        t.setHotbarSlot(inventory.heldSlot());
        t.setItemInHand(inventory.held());
        t.setPlayerPosition(Vector3f.from(x, y + EYE_HEIGHT, z));
        t.setClickPosition(Vector3f.from(u.getCursorX(), u.getCursorY(), u.getCursorZ()));
        t.setBlockDefinition(() -> 0);
        upstream.send(t);
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
