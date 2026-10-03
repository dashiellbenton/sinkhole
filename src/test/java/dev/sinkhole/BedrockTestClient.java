package dev.sinkhole;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless Bedrock client that behaves like the vanilla handshake, for testing the proxy without a game install.
 * Usage: BedrockTestClient [host] [port] [seconds]
 */
public final class BedrockTestClient {
    static final Map<String, Integer> seen = new TreeMap<>();
    static volatile boolean disconnected;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 35653;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;

        NioEventLoopGroup group = new NioEventLoopGroup();
        Channel ch = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_GUID, System.nanoTime())
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession session) {
                        session.setLogging(false);
                        session.setPacketHandler(new Handler(session));
                        RequestNetworkSettingsPacket req = new RequestNetworkSettingsPacket();
                        req.setProtocolVersion(Bedrock_v2193.CODEC.getProtocolVersion());
                        session.setCodec(Bedrock_v2193.CODEC);
                        session.sendPacketImmediately(req);
                    }
                })
                .connect(new InetSocketAddress(host, port)).syncUninterruptibly().channel();

        Thread.sleep(seconds * 1000L);
        System.out.println("--- packets received ---");
        seen.forEach((k, v) -> System.out.println(v + "  " + k));
        ch.close();
        group.shutdownGracefully();
        System.exit(disconnected ? 2 : 0);
    }

    static void note(Object p) {
        seen.merge(p.getClass().getSimpleName(), 1, Integer::sum);
    }

    static final class Handler implements BedrockPacketHandler {
        private final BedrockClientSession s;

        Handler(BedrockClientSession s) {
            this.s = s;
        }

        @Override
        public PacketSignal handlePacket(BedrockPacket p) {
            note(p);
            if (p instanceof DisconnectPacket d) {
                System.out.println("Server disconnect: " + d.getKickMessage() + " / " + d.getReason());
            }
            return PacketSignal.UNHANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            System.out.println("DISCONNECTED: " + reason);
            disconnected = true;
        }

        @Override
        public PacketSignal handle(DisconnectPacket p) {
            System.out.println("Server disconnect: " + p.getKickMessage());
            disconnected = true;
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(NetworkSettingsPacket p) {
            s.setCompression(PacketCompressionAlgorithm.ZLIB);
            try {
                java.security.KeyPair kp = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils.createKeyPair();
                String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
                long now = System.currentTimeMillis() / 1000;
                String identity = jwt(kp, pub, "{\"identityPublicKey\":\"" + pub + "\",\"nbf\":" + (now - 60) + ",\"exp\":" + (now + 86400)
                        + ",\"extraData\":{\"displayName\":\"BedrockGuy\",\"identity\":\"11111111-1111-1111-1111-111111111111\",\"XUID\":\"\"}}");
                String client = jwt(kp, pub, "{\"ServerAddress\":\"127.0.0.1:19132\",\"ThirdPartyName\":\"BedrockGuy\",\"GameVersion\":\"1.26.52\",\"DeviceOS\":7,\"DeviceModel\":\"Test\",\"LanguageCode\":\"en_US\",\"CurrentInputMode\":1,\"DefaultInputMode\":1,\"GuiScale\":0,\"UIProfile\":0,\"SelfSignedId\":\"11111111-1111-1111-1111-111111111111\",\"ClientRandomId\":1}");
                LoginPacket login = new LoginPacket();
                login.setProtocolVersion(Bedrock_v2193.CODEC.getProtocolVersion());
                login.setAuthPayload(new CertificateChainPayload(List.of(identity), AuthType.SELF_SIGNED));
                login.setClientJwt(client);
                s.sendPacketImmediately(login);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return PacketSignal.HANDLED;
        }

        static String jwt(java.security.KeyPair kp, String pub, String payload) throws Exception {
            org.jose4j.jws.JsonWebSignature jws = new org.jose4j.jws.JsonWebSignature();
            jws.setPayload(payload);
            jws.setAlgorithmHeaderValue("ES384");
            jws.setHeader("x5u", pub);
            jws.setKey(kp.getPrivate());
            return jws.getCompactSerialization();
        }
        // unused
        private PacketSignal unused() {
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayStatusPacket p) {
            System.out.println("PlayStatus " + p.getStatus());
            if (p.getStatus() == PlayStatusPacket.Status.PLAYER_SPAWN) {
                SetLocalPlayerAsInitializedPacket init = new SetLocalPlayerAsInitializedPacket();
                init.setRuntimeEntityId(1);
                s.sendPacket(init);
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePacksInfoPacket p) {
            ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
            r.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
            s.sendPacket(r);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackStackPacket p) {
            ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
            r.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
            s.sendPacket(r);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(StartGamePacket p) {
            System.out.println("StartGame: entity=" + p.getRuntimeEntityId() + " pos=" + p.getPlayerPosition()
                    + " items=" + p.getItemDefinitions().size());
            RequestChunkRadiusPacket r = new RequestChunkRadiusPacket();
            r.setRadius(8);
            r.setMaxRadius(8);
            s.sendPacket(r);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(TextPacket p) {
            System.out.println("Text[" + p.getType() + "] " + p.getSourceName() + ": " + p.getMessage());
            return PacketSignal.HANDLED;
        }
    }
}
