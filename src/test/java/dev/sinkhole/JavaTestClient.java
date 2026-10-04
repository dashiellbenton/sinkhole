package dev.sinkhole;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

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
        Thread.sleep(seconds * 1000L);
        System.out.println("--- packets received ---");
        seen.forEach((k, v) -> System.out.println(v + "  " + k));
        session.disconnect("done");
        System.exit(0);
    }
}
