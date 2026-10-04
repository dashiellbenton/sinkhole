package dev.sinkhole;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.TreeMap;

/** Headless Java client for testing the proxy. Usage: JavaTestClient [host] [port] [seconds] [name] */
public final class JavaTestClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 35653;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        String name = args.length > 3 ? args[3] : "JavaDude";

        Map<String, Integer> seen = new TreeMap<>();
        int[] chunks = {0};
        int[] zombie = {-1};
        var session = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(new MinecraftProtocol(name))
                .create();
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet p) {
                seen.merge(p.getClass().getSimpleName(), 1, Integer::sum);
                if (p instanceof ClientboundSystemChatPacket c) {
                    System.out.println("CHAT: " + c.getContent());
                }
                if (p instanceof ClientboundAddEntityPacket a && a.getType() == EntityType.ZOMBIE) {
                    zombie[0] = a.getEntityId();
                    System.out.println("zombie spawned id=" + a.getEntityId() + " at " + a.getX() + "," + a.getY() + "," + a.getZ());
                }
                if (p instanceof ClientboundSetHealthPacket h) {
                    System.out.println("health=" + h.getHealth() + " food=" + h.getFood());
                }
                if (p instanceof ClientboundContainerSetContentPacket c) {
                    System.out.println("inventory slot36=" + c.getItems()[36]);
                }
                if (p instanceof ClientboundLevelChunkWithLightPacket c && chunks[0]++ == 0) {
                    System.out.println("first chunk " + c.getX() + "," + c.getZ() + " bytes=" + c.getChunkData().length);
                }
            }

            @Override
            public void packetError(org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent e) {
                System.out.println("PACKET ERROR: " + e.getCause());
                e.getCause().printStackTrace(System.out);
            }

            @Override
            public void disconnected(DisconnectedEvent e) {
                System.out.println("DISCONNECTED: " + e.getReason());
            }
        });
        session.connect();
        Thread.sleep(Long.getLong("sinkhole.wait", 4000L));
        session.send(new ServerboundChatPacket("hello from " + name + " (I am JavaDude)", System.currentTimeMillis(), 0L, null, 0, new java.util.BitSet(), 0));
        session.send(new ServerboundChatCommandPacket("time query daytime"));
        session.send(new ServerboundSetCarriedItemPacket(0));
        session.send(new ServerboundMovePlayerPosRotPacket(true, false, 8.5, -52, 8.5, 90f, 10f));
        session.send(new ServerboundAttackPacket(zombie[0]));
        session.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        session.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, Vector3i.from(8, -53, 8), Direction.UP, 1));
        session.send(new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, Vector3i.from(8, -53, 8), Direction.UP, 2));
        session.send(new ServerboundUseItemOnPacket(Vector3i.from(8, -53, 8), Direction.UP, Hand.MAIN_HAND, 0.5f, 1f, 0.5f, false, false, 3));
        Thread.sleep(seconds * 1000L);
        System.out.println("--- packets received ---");
        seen.forEach((k, v) -> System.out.println(v + "  " + k));
        session.disconnect("done");
        System.exit(0);
    }
}
