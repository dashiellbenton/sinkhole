package dev.sinkhole;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Tracks the other entities of the Bedrock world and mirrors them to the Java client. */
public final class Entities {
    private static final float EYE_HEIGHT = 1.62f;

    private static final class Tracked {
        final int javaId;
        final long unique;
        final boolean player;
        UUID uuid;
        double x, y, z;
        float yaw, pitch, headYaw;

        Tracked(int javaId, long unique, boolean player) {
            this.javaId = javaId;
            this.unique = unique;
            this.player = player;
        }
    }

    private final Session client;
    private final Map<Long, Tracked> byRuntime = new HashMap<>();
    private final Map<Long, Long> runtimeByUnique = new HashMap<>();
    private final Map<Integer, Long> runtimeByJava = new HashMap<>();
    private int nextId = 1000;
    private final String ownName;
    private final String rewriteFrom;

    public Entities(Session java, String gamertag, String javaName) {
        this.client = java;
        this.ownName = javaName;
        this.rewriteFrom = gamertag;
    }

    private Tracked track(long runtime, long unique, boolean player) {
        Tracked t = new Tracked(nextId++, unique, player);
        byRuntime.put(runtime, t);
        runtimeByUnique.put(unique, runtime);
        runtimeByJava.put(t.javaId, runtime);
        return t;
    }

    /** Bedrock runtime id for a Java entity id the client refers to, or -1. */
    public long runtimeIdOf(int javaId) {
        Long r = runtimeByJava.get(javaId);
        return r == null ? -1 : r;
    }

    public void addPlayer(AddPlayerPacket p) {
        Tracked t = track(p.getRuntimeEntityId(), p.getUniqueEntityId(), true);
        t.uuid = p.getUuid();
        String name = Bridge.rename(p.getUsername(), rewriteFrom, ownName);
        GameProfile profile = new GameProfile(p.getUuid(), name.length() > 16 ? name.substring(0, 16) : name);
        PlayerListEntry entry = new PlayerListEntry(p.getUuid(), profile, true, 0, GameMode.SURVIVAL, null, true, 0, null, 0, null, null);
        client.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(PlayerListEntryAction.ADD_PLAYER, PlayerListEntryAction.UPDATE_LISTED), new PlayerListEntry[]{entry}));
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY() - EYE_HEIGHT;
        t.z = pos.getZ();
        t.pitch = p.getRotation().getX();
        t.yaw = p.getRotation().getY();
        t.headYaw = p.getRotation().getZ();
        client.send(new ClientboundAddEntityPacket(t.javaId, p.getUuid(), EntityType.PLAYER, t.x, t.y, t.z, t.yaw, t.headYaw, t.pitch));
        metadata(p.getRuntimeEntityId(), p.getMetadata());
    }

    public void addEntity(AddEntityPacket p) {
        EntityType type = javaType(p.getIdentifier());
        if (type == null) {
            return; // no Java equivalent
        }
        Tracked t = track(p.getRuntimeEntityId(), p.getUniqueEntityId(), false);
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY();
        t.z = pos.getZ();
        t.pitch = p.getRotation().getX();
        t.yaw = p.getRotation().getY();
        t.headYaw = p.getHeadRotation();
        client.send(new ClientboundAddEntityPacket(t.javaId, UUID.randomUUID(), type, t.x, t.y, t.z, t.yaw, t.headYaw, t.pitch));
        metadata(p.getRuntimeEntityId(), p.getMetadata());
    }

    /** Dropped items: a Java item entity plus its item-stack metadata. */
    public void addItem(AddItemEntityPacket p, Inventory inv) {
        var stack = inv.toJava(p.getItemInHand());
        if (stack == null) {
            return;
        }
        Tracked t = track(p.getRuntimeEntityId(), p.getUniqueEntityId(), false);
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY();
        t.z = pos.getZ();
        client.send(new ClientboundAddEntityPacket(t.javaId, UUID.randomUUID(), EntityType.ITEM, t.x, t.y, t.z, 0f, 0f, 0f));
        client.send(new ClientboundSetEntityDataPacket(t.javaId, new EntityMetadata<?, ?>[]{
                new ObjectEntityMetadata<>(8, MetadataTypes.ITEM_STACK, stack)}));
    }

    /** Names, sneaking, sprinting, fire, invisibility. */
    public void metadata(long runtimeId, EntityDataMap data) {
        Tracked t = byRuntime.get(runtimeId);
        if (t == null || data == null) {
            return;
        }
        List<EntityMetadata<?, ?>> out = new java.util.ArrayList<>();
        var flags = data.getFlags();
        if (flags != null && !flags.isEmpty()) {
            byte b = 0;
            if (Boolean.TRUE.equals(flags.get(EntityFlag.ON_FIRE))) {
                b |= 0x01;
            }
            if (Boolean.TRUE.equals(flags.get(EntityFlag.SNEAKING))) {
                b |= 0x02;
            }
            if (Boolean.TRUE.equals(flags.get(EntityFlag.SPRINTING))) {
                b |= 0x08;
            }
            if (Boolean.TRUE.equals(flags.get(EntityFlag.INVISIBLE))) {
                b |= 0x20;
            }
            out.add(new ByteEntityMetadata(0, MetadataTypes.BYTE, b));
            out.add(new ObjectEntityMetadata<>(6, MetadataTypes.POSE,
                    Boolean.TRUE.equals(flags.get(EntityFlag.SNEAKING)) ? Pose.SNEAKING : Pose.STANDING));
        }
        CharSequence name = data.get(EntityDataTypes.NAME);
        if (name != null && !name.toString().isEmpty() && !t.player) {
            out.add(new ObjectEntityMetadata<>(2, MetadataTypes.OPTIONAL_COMPONENT,
                    java.util.Optional.of(net.kyori.adventure.text.Component.text(Bridge.rename(name.toString(), rewriteFrom, ownName)))));
            out.add(new BooleanEntityMetadata(3, MetadataTypes.BOOLEAN, true));
        }
        if (!out.isEmpty()) {
            client.send(new ClientboundSetEntityDataPacket(t.javaId, out.toArray(new EntityMetadata<?, ?>[0])));
        }
    }

    public void remove(RemoveEntityPacket p) {
        Long runtime = runtimeByUnique.remove(p.getUniqueEntityId());
        if (runtime == null) {
            return;
        }
        Tracked t = byRuntime.remove(runtime);
        if (t != null) {
            runtimeByJava.remove(t.javaId);
            client.send(new ClientboundRemoveEntitiesPacket(new int[]{t.javaId}));
            if (t.uuid != null) {
                client.send(new ClientboundPlayerInfoRemovePacket(List.of(t.uuid)));
            }
        }
    }

    public void move(MoveEntityAbsolutePacket p) {
        Tracked t = byRuntime.get(p.getRuntimeEntityId());
        if (t == null) {
            return;
        }
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY() - (t.player ? EYE_HEIGHT : 0);
        t.z = pos.getZ();
        t.pitch = p.getRotation().getX();
        t.yaw = p.getRotation().getY();
        t.headYaw = p.getRotation().getZ();
        sendTeleport(t, p.isOnGround());
    }

    public void move(MovePlayerPacket p) {
        Tracked t = byRuntime.get(p.getRuntimeEntityId());
        if (t == null) {
            return;
        }
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY() - EYE_HEIGHT;
        t.z = pos.getZ();
        t.pitch = p.getRotation().getX();
        t.yaw = p.getRotation().getY();
        t.headYaw = p.getRotation().getZ();
        sendTeleport(t, p.isOnGround());
    }

    public void move(MoveEntityDeltaPacket p) {
        Tracked t = byRuntime.get(p.getRuntimeEntityId());
        if (t == null) {
            return;
        }
        // Absolute components are only present for the flags set; the packet object carries 0 otherwise.
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_X)) {
            t.x = p.getX();
        }
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Y)) {
            t.y = p.getY() - (t.player ? EYE_HEIGHT : 0);
        }
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Z)) {
            t.z = p.getZ();
        }
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_PITCH)) {
            t.pitch = p.getPitch();
        }
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_YAW)) {
            t.yaw = p.getYaw();
        }
        if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW)) {
            t.headYaw = p.getHeadYaw();
        }
        sendTeleport(t, p.getFlags().contains(MoveEntityDeltaPacket.Flag.ON_GROUND));
    }

    private void sendTeleport(Tracked t, boolean onGround) {
        client.send(new ClientboundTeleportEntityPacket(t.javaId, Vector3d.from(t.x, t.y, t.z), Vector3d.ZERO, t.yaw, t.pitch, List.of(), onGround));
        client.send(new ClientboundRotateHeadPacket(t.javaId, t.headYaw));
    }

    /** Hurt/death animations. */
    public void event(EntityEventPacket e, long selfRuntime, int selfJavaId) {
        int javaId;
        if (e.getRuntimeEntityId() == selfRuntime) {
            javaId = selfJavaId;
        } else {
            Tracked t = byRuntime.get(e.getRuntimeEntityId());
            if (t == null) {
                return;
            }
            javaId = t.javaId;
        }
        if (e.getType() == EntityEventType.HURT) {
            client.send(new ClientboundEntityEventPacket(javaId, EntityEvent.LIVING_HURT));
        } else if (e.getType() == EntityEventType.DEATH) {
            client.send(new ClientboundEntityEventPacket(javaId, EntityEvent.LIVING_DEATH));
        }
    }

    /** Tab list entries of other players. */
    @SuppressWarnings("deprecation")
    public void playerList(PlayerListPacket p) {
        if (p.getAction() == PlayerListPacket.Action.ADD) {
            List<PlayerListEntry> entries = new java.util.ArrayList<>();
            for (PlayerListPacket.Entry e : p.getEntries()) {
                String name = Bridge.rename(e.getName(), rewriteFrom, ownName);
                entries.add(new PlayerListEntry(e.getUuid(), new GameProfile(e.getUuid(), name.length() > 16 ? name.substring(0, 16) : name),
                        true, 0, GameMode.SURVIVAL, null, true, 0, null, 0, null, null));
            }
            if (!entries.isEmpty()) {
                client.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(PlayerListEntryAction.ADD_PLAYER, PlayerListEntryAction.UPDATE_LISTED), entries.toArray(new PlayerListEntry[0])));
            }
        } else {
            List<UUID> uuids = new java.util.ArrayList<>();
            p.getEntries().forEach(e -> uuids.add(e.getUuid()));
            if (!uuids.isEmpty()) {
                client.send(new ClientboundPlayerInfoRemovePacket(uuids));
            }
        }
    }

    public void clear() {
        if (!byRuntime.isEmpty()) {
            int[] ids = byRuntime.values().stream().mapToInt(t -> t.javaId).toArray();
            client.send(new ClientboundRemoveEntitiesPacket(ids));
        }
        byRuntime.clear();
        runtimeByUnique.clear();
        runtimeByJava.clear();
    }

    /** Most Bedrock entity identifiers are the Java entity type name. */
    private static EntityType javaType(String identifier) {
        if (identifier == null || !identifier.startsWith("minecraft:")) {
            return null;
        }
        String name = identifier.substring("minecraft:".length());
        name = switch (name) {
            case "xp_orb" -> "experience_orb";
            case "xp_bottle" -> "experience_bottle";
            case "ender_crystal" -> "end_crystal";
            case "fireworks_rocket" -> "firework_rocket";
            case "lightning_bolt" -> "lightning_bolt";
            case "thrown_trident" -> "trident";
            case "snowball", "egg", "ender_pearl" -> name;
            case "tnt" -> "tnt";
            default -> name;
        };
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
