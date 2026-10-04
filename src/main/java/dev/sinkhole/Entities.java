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
        double x, y, z;
        float yaw, pitch, headYaw;

        Tracked(int javaId, long unique, boolean player) {
            this.javaId = javaId;
            this.unique = unique;
            this.player = player;
        }
    }

    private final Session java;
    private final Map<Long, Tracked> byRuntime = new HashMap<>();
    private final Map<Long, Long> runtimeByUnique = new HashMap<>();
    private final Map<Integer, Long> runtimeByJava = new HashMap<>();
    private int nextId = 1000;
    private final String ownName;
    private final String rewriteFrom;

    public Entities(Session java, String gamertag, String javaName) {
        this.java = java;
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
        String name = Bridge.rename(p.getUsername(), rewriteFrom, ownName);
        GameProfile profile = new GameProfile(p.getUuid(), name.length() > 16 ? name.substring(0, 16) : name);
        PlayerListEntry entry = new PlayerListEntry(p.getUuid(), profile, true, 0, GameMode.SURVIVAL, null, true, 0, null, 0, null, null);
        java.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(PlayerListEntryAction.ADD_PLAYER, PlayerListEntryAction.UPDATE_LISTED), new PlayerListEntry[]{entry}));
        Vector3f pos = p.getPosition();
        t.x = pos.getX();
        t.y = pos.getY() - EYE_HEIGHT;
        t.z = pos.getZ();
        t.pitch = p.getRotation().getX();
        t.yaw = p.getRotation().getY();
        t.headYaw = p.getRotation().getZ();
        java.send(new ClientboundAddEntityPacket(t.javaId, p.getUuid(), EntityType.PLAYER, t.x, t.y, t.z, t.yaw, t.headYaw, t.pitch));
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
        java.send(new ClientboundAddEntityPacket(t.javaId, UUID.randomUUID(), type, t.x, t.y, t.z, t.yaw, t.headYaw, t.pitch));
    }

    public void remove(RemoveEntityPacket p) {
        Long runtime = runtimeByUnique.remove(p.getUniqueEntityId());
        if (runtime == null) {
            return;
        }
        Tracked t = byRuntime.remove(runtime);
        if (t != null) {
            runtimeByJava.remove(t.javaId);
            java.send(new ClientboundRemoveEntitiesPacket(new int[]{t.javaId}));
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
        java.send(new ClientboundTeleportEntityPacket(t.javaId, Vector3d.from(t.x, t.y, t.z), Vector3d.ZERO, t.yaw, t.pitch, List.of(), onGround));
        java.send(new ClientboundRotateHeadPacket(t.javaId, t.headYaw));
    }

    public void clear() {
        if (!byRuntime.isEmpty()) {
            int[] ids = byRuntime.values().stream().mapToInt(t -> t.javaId).toArray();
            java.send(new ClientboundRemoveEntitiesPacket(ids));
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
