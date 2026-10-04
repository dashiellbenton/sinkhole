package dev.sinkhole;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.jws.JsonWebSignature;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Our connection to the real Bedrock server, behaving like a Bedrock game client. Handles the login, encryption and
 * resource-pack handshake itself, then hands every packet to the {@link Listener}.
 */
public final class BedrockUpstream implements BedrockPacketHandler {
    public interface Listener {
        /** Called once the handshake is over, for every packet from the server (on the network thread). */
        void onPacket(BedrockPacket packet);

        void onDisconnect(String reason);
    }

    private static final boolean DEBUG = System.getenv("SINKHOLE_DEBUG") != null;

    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private final AuthService.Identity identity;
    private final String host;
    private final int port;
    private final Listener listener;
    private volatile BedrockClientSession session;
    private boolean closed;

    public BedrockUpstream(AuthService.Identity identity, String host, int port, Listener listener) {
        this.identity = identity;
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void connect() {
        new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_GUID, System.nanoTime())
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession s) {
                        session = s;
                        s.setLogging(DEBUG);
                        s.setCodec(BedrockCodecs.current());
                        s.setPacketHandler(BedrockUpstream.this);
                        RequestNetworkSettingsPacket req = new RequestNetworkSettingsPacket();
                        req.setProtocolVersion(BedrockCodecs.current().getProtocolVersion());
                        s.sendPacketImmediately(req);
                    }
                })
                .connect(new InetSocketAddress(host, port))
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        fail("Could not reach the Bedrock server " + host + ":" + port + " (" + f.cause().getMessage() + ")");
                    }
                });
    }

    public void send(BedrockPacket packet) {
        BedrockClientSession s = session;
        if (s != null) {
            s.sendPacket(packet);
        }
    }

    public void close() {
        closed = true;
        BedrockClientSession s = session;
        if (s != null) {
            s.disconnect();
        }
        group.shutdownGracefully();
    }

    private void fail(String reason) {
        if (!closed) {
            closed = true;
            listener.onDisconnect(reason);
        }
    }

    // ------------------------------------------------------------------ handshake

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (DEBUG) {
            System.out.println("[debug] bedrock server -> proxy: " + packet.getClass().getSimpleName());
        }
        if (packet instanceof NetworkSettingsPacket || packet instanceof ServerToClientHandshakePacket
                || packet instanceof ResourcePacksInfoPacket || packet instanceof ResourcePackStackPacket) {
            return packet.handle(this); // the handle(...) methods below
        }
        listener.onPacket(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        session.setCompression(packet.getCompressionAlgorithm());
        try {
            String pub = Base64.getEncoder().encodeToString(identity.key().getPublic().getEncoded());
            LoginPacket login = new LoginPacket();
            login.setProtocolVersion(BedrockCodecs.current().getProtocolVersion());
            login.setAuthPayload(new CertificateChainPayload(identity.chain(), identity.authType()));
            login.setClientJwt(Jwts.sign(identity.key(), pub,
                    ClientData.payload(identity, host + ":" + port, BedrockCodecs.current().getMinecraftVersion())));
            session.sendPacketImmediately(login);
        } catch (Exception e) {
            fail("Could not build the Bedrock login: " + e.getMessage());
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getJwt());
            PublicKey serverKey = EncryptionUtils.parseKey(jws.getHeader("x5u"));
            byte[] salt = Base64.getDecoder().decode(new com.google.gson.JsonParser()
                    .parse(jws.getUnverifiedPayload()).getAsJsonObject().get("salt").getAsString());
            SecretKey key = EncryptionUtils.getSecretKey(identity.key().getPrivate(), serverKey, salt);
            session.enableEncryption(key);
            session.sendPacketImmediately(new ClientToServerHandshakePacket());
        } catch (Exception e) {
            fail("Encryption handshake with the Bedrock server failed: " + e.getMessage());
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePacksInfoPacket packet) {
        // Packs are not downloaded; claim to have everything. Servers that force packs may refuse this.
        ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
        r.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        session.sendPacket(r);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackStackPacket packet) {
        ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
        r.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        session.sendPacket(r);
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        fail(String.valueOf(reason));
    }
}
