package dev.sinkhole;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/** RakNet listener the Bedrock game connects to. Only one player is ever proxied at a time. */
public final class ProxyServer {
    private final SinkholeConfig config;
    private final AuthService auth;
    private final AtomicReference<ProxySession> active = new AtomicReference<>();
    private final EventLoopGroup group = new NioEventLoopGroup();
    private Channel channel;
    private ChunkTranslator chunks;

    public ProxyServer(SinkholeConfig config, AuthService auth) {
        this.config = config;
        this.auth = auth;
    }

    public void start() throws Exception {
        chunks = new ChunkTranslator(new BlockMapper());
        BedrockPong pong = new BedrockPong()
                .edition("MCPE")
                .motd("SinkholeMC")
                .subMotd(auth.javaName())
                .playerCount(0)
                .maximumPlayerCount(1)
                .gameType("Survival")
                .protocolVersion(Bedrock_v2193.CODEC.getProtocolVersion())
                .version(Bedrock_v2193.CODEC.getMinecraftVersion())
                .ipv4Port(config.port)
                .serverId(System.nanoTime());

        ChannelFuture f = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    protected void initSession(BedrockServerSession session) {
                        ProxySession proxy = new ProxySession(config, auth, session, chunks);
                        // Single-user: refuse a second concurrent client.
                        if (!active.compareAndSet(null, proxy)) {
                            session.disconnect("SinkholeMC only supports one player at a time.");
                            return;
                        }
                        session.setLogging(false);
                        session.setPacketHandler(proxy);
                        session.getPeer().getChannel().closeFuture().addListener(x -> {
                            proxy.close();
                            active.compareAndSet(proxy, null);
                        });
                    }
                })
                .bind(new InetSocketAddress(config.bindAddress, config.port))
                .sync();
        channel = f.channel();
    }

    public void stop() {
        ProxySession s = active.get();
        if (s != null) {
            s.close();
        }
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
    }
}
