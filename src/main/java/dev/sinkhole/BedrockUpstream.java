package dev.sinkhole;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
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
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
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
import java.util.UUID;

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

    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final AuthService.Identity identity;
    private final String host;
    private final int port;
    private final Listener listener;
    private volatile BedrockClientSession session;
    private boolean closed;

    private final String transport;
    private NetherNetClient nether;
    private boolean usingNetherNet;

    public BedrockUpstream(AuthService.Identity identity, String host, int port, String transport, Listener listener) {
        this.identity = identity;
        this.host = host;
        this.port = port;
        this.transport = transport == null ? "auto" : transport;
        this.listener = listener;
    }

    /** Connects over RakNet or NetherNet ("auto" asks the server's HTTP endpoint which one it speaks). */
    public void connect() {
        Thread t = new Thread(() -> {
            boolean nn = switch (transport) {
                case "nethernet" -> true;
                case "raknet" -> false;
                default -> NetherNetClient.isNetherNetServer(host, port);
            };
            if (nn) {
                connectNetherNet();
            } else {
                connectRakNet();
            }
        }, "sinkhole-bedrock-connect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * NetherNet: the WebRTC data channel is bridged into a normal Bedrock netty pipeline through an in-JVM local channel
     * pair, so the packet codecs, compression and session handling are exactly the ones used for RakNet.
     */
    private void connectNetherNet() {
        usingNetherNet = true;
        try {
            io.netty.channel.EventLoopGroup local = new MultiThreadIoEventLoopGroup(1, io.netty.channel.local.LocalIoHandler.newFactory());
            io.netty.channel.local.LocalAddress address = new io.netty.channel.local.LocalAddress("sinkhole-nn-" + System.nanoTime());
            java.util.concurrent.atomic.AtomicReference<Channel> serverSide = new java.util.concurrent.atomic.AtomicReference<>();

            nether = new NetherNetClient(new NetherNetClient.Listener() {
                @Override
                public void onMessage(io.netty.buffer.ByteBuf data) {
                    Channel c = serverSide.get();
                    if (c == null) {
                        data.release();
                    } else {
                        c.writeAndFlush(data);
                    }
                }

                @Override
                public void onClosed(String reason) {
                    fail("The NetherNet connection closed: " + reason);
                }
            });
            // Real accounts present their multiplayer token (bound to the login key); offline mode signs its own.
            String nnToken = identity.loginToken() != null ? identity.loginToken() : identity.serviceToken();
            java.util.function.UnaryOperator<String> assertion = NetherNetIdentity.assertion(identity.key(), nnToken, nnToken == null ? NetherNetIdentity.DEFAULT_DOMAIN : NetherNetIdentity.domainOf(nnToken));
            nether.connect(host, port, assertion);

            new io.netty.bootstrap.ServerBootstrap()
                    .group(local)
                    .channel(io.netty.channel.local.LocalServerChannel.class)
                    .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.local.LocalChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.local.LocalChannel ch) {
                            serverSide.set(ch);
                            ch.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                                    nether.send((io.netty.buffer.ByteBuf) msg);
                                }
                            });
                        }
                    }).bind(address).sync();

            new Bootstrap()
                    .group(local)
                    .channel(io.netty.channel.local.LocalChannel.class)
                    .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.local.LocalChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.local.LocalChannel ch) {
                            io.netty.channel.ChannelPipeline p = ch.pipeline();
                            // Same stack as BedrockClientInitializer, with NetherNet framing instead of the RakNet frame id.
                            p.addLast(org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec.NAME, new NetherNetFraming()); // encryption is inserted relative to this name
                            p.addLast("compression-codec", new org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec(
                                    BedrockClientInitializer.getCompression(org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm.ZLIB, 11, true), false));
                            p.addLast("bedrock-batch-decoder", new org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder());
                            p.addLast("bedrock-batch-encoder", new org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder());
                            p.addLast("bedrock-packet-codec", new org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3());
                            p.addLast("bedrock-peer", new org.cloudburstmc.protocol.bedrock.BedrockPeer(ch, (peer, subClientId) -> {
                                BedrockClientSession s = new BedrockClientSession(peer, subClientId);
                                initSession(s);
                                return s;
                            }) {
                                @Override
                                public int getRakVersion() {
                                    return 11; // the stock peer reads this from RakNet channel options, which a local channel lacks
                                }
                            });
                        }
                    }).connect(address).sync();
        } catch (Exception e) {
            fail("Could not connect to the NetherNet server " + host + ":" + port + ": " + e.getMessage());
        }
    }

    private void initSession(BedrockClientSession s) {
        session = s;
        s.setLogging(DEBUG);
        s.setCodec(BedrockCodecs.current());
        s.setPacketHandler(BedrockUpstream.this);
        RequestNetworkSettingsPacket req = new RequestNetworkSettingsPacket();
        req.setProtocolVersion(BedrockCodecs.current().getProtocolVersion());
        s.sendPacketImmediately(req);
    }

    private void connectRakNet() {
        new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_GUID, -(Math.abs(new java.util.Random().nextLong() >> 1) + 1)) // real clients use negative GUIDs; some servers insist
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession s) {
                        BedrockUpstream.this.initSession(s);
                    }
                })
                .connect(new InetSocketAddress(host, port))
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        fail("Could not reach the Bedrock server " + host + ":" + port + " (" + f.cause() + ")");
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
        if (nether != null) {
            nether.close();
        }
        group.shutdownGracefully();
    }

    private void fail(String reason) {
        if (!closed) {
            closed = true;
            listener.onDisconnect(reason);
        }
    }

    private void setItemDefinitions(java.util.List<ItemDefinition> items) {
        session.getPeer().getCodecHelper().setItemDefinitions(SimpleDefinitionRegistry.<ItemDefinition>builder().addAll(items).build());
    }

    /** Packets that carry items/blocks can only be decoded once the session knows the server's definitions. */
    @SuppressWarnings("deprecation") // StartGame still carries item definitions on older servers
    private void registerDefinitions(StartGamePacket sg) {
        var helper = session.getPeer().getCodecHelper();
        if (!sg.getItemDefinitions().isEmpty()) {
            setItemDefinitions(sg.getItemDefinitions());
        }
        helper.setBlockDefinitions(new DefinitionRegistry<BlockDefinition>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return () -> runtimeId;
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ handshake

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (DEBUG) {
            System.out.println("[debug] bedrock server -> proxy: " + packet.getClass().getSimpleName());
        }
        if (packet instanceof NetworkSettingsPacket || packet instanceof ServerToClientHandshakePacket
                || packet instanceof ResourcePacksInfoPacket || packet instanceof ResourcePackStackPacket
                || packet instanceof ResourcePackDataInfoPacket || packet instanceof ResourcePackChunkDataPacket) {
            return packet.handle(this); // the handle(...) methods below
        }
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket v) {
            System.err.println("[Sinkhole] The Bedrock server reported a protocol violation: " + v);
        }
        if (packet instanceof StartGamePacket sg) {
            registerDefinitions(sg);
        } else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket ic) {
            setItemDefinitions(ic.getItems());
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
            login.setAuthPayload(identity.payload());
            login.setClientJwt(Jwts.sign(identity.key(), pub,
                    ClientData.payload(identity, usingNetherNet ? "http://" + host + ":" + port + ":" + port : host + ":" + port, BedrockCodecs.current().getMinecraftVersion())));
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
            byte[] salt = Base64.getDecoder().decode(com.google.gson.JsonParser
                    .parseString(jws.getUnverifiedPayload()).getAsJsonObject().get("salt").getAsString());
            SecretKey key = EncryptionUtils.getSecretKey(identity.key().getPrivate(), serverKey, salt);
            if (!usingNetherNet) {
                session.enableEncryption(key); // NetherNet is already encrypted by DTLS, so only the acknowledgement is sent
            }
            session.sendPacketImmediately(new ClientToServerHandshakePacket());
        } catch (Exception e) {
            fail("Encryption handshake with the Bedrock server failed: " + e.getMessage());
        }
        return PacketSignal.HANDLED;
    }

    // Resource packs: the server's packs are downloaded (and thrown away) so that servers which require them accept us.
    private final java.util.Map<UUID, Long> packChunks = new java.util.LinkedHashMap<>();
    private final java.util.Set<UUID> packsPending = new java.util.HashSet<>();

    @Override
    @SuppressWarnings("deprecation")
    public PacketSignal handle(ResourcePacksInfoPacket packet) {
        java.util.List<String> wanted = new java.util.ArrayList<>();
        java.util.List<ResourcePacksInfoPacket.Entry> all = new java.util.ArrayList<>(packet.getResourcePackInfos());
        all.addAll(packet.getBehaviorPackInfos());
        for (ResourcePacksInfoPacket.Entry e : all) {
            if (e.getCdnUrl() == null || e.getCdnUrl().isEmpty()) {
                wanted.add(e.getPackId() + "_" + e.getPackVersion());
                packsPending.add(e.getPackId());
            }
        }
        ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
        if (wanted.isEmpty()) {
            r.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        } else {
            r.setStatus(ResourcePackClientResponsePacket.Status.SEND_PACKS);
            r.getPackIds().addAll(wanted);
        }
        session.sendPacket(r);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackDataInfoPacket packet) {
        packChunks.put(packet.getPackId(), packet.getChunkCount());
        requestChunk(packet.getPackId(), packet.getPackVersion(), 0);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackChunkDataPacket packet) {
        long count = packChunks.getOrDefault(packet.getPackId(), 0L);
        if (packet.getChunkIndex() + 1 < count) {
            requestChunk(packet.getPackId(), packet.getPackVersion(), packet.getChunkIndex() + 1);
        } else {
            packsPending.remove(packet.getPackId());
            if (packsPending.isEmpty()) {
                ResourcePackClientResponsePacket r = new ResourcePackClientResponsePacket();
                r.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
                session.sendPacket(r);
            }
        }
        return PacketSignal.HANDLED;
    }

    private void requestChunk(UUID pack, String version, int index) {
        ResourcePackChunkRequestPacket req = new ResourcePackChunkRequestPacket();
        req.setPackId(pack);
        req.setPackVersion(version);
        req.setChunkIndex(index);
        session.sendPacket(req);
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
