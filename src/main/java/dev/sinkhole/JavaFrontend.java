package dev.sinkhole;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.server.ServerAdapter;
import org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/** The Java Edition server socket the user's Java client connects to. One player at a time. */
public final class JavaFrontend {
    private final SinkholeConfig config;
    private final AuthService auth;
    private final Registries registries;
    private final BedrockChunks chunks;
    private final GameData gameData;
    private final AtomicReference<Bridge> active = new AtomicReference<>();
    private NetworkServer server;

    public JavaFrontend(SinkholeConfig config, AuthService auth, Registries registries, BedrockChunks chunks, GameData gameData) {
        this.gameData = gameData;
        this.config = config;
        this.auth = auth;
        this.registries = registries;
        this.chunks = chunks;
    }

    public void start() {
        server = new NetworkServer(new InetSocketAddress(config.bindAddress, config.port), MinecraftProtocol::new);
        // The local Java client is not authenticated: the Xbox login happens on the Bedrock side.
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, false);
        server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, false);
        server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, 256);
        server.setGlobalFlag(MinecraftConstants.SERVER_INFO_BUILDER_KEY, s -> new ServerStatusInfo(
                Component.text("SinkholeMC -> " + config.server),
                new PlayerInfo(1, active.get() == null ? 0 : 1, java.util.List.of()),
                new VersionInfo(new MinecraftProtocol().getCodec().getMinecraftVersion(), new MinecraftProtocol().getCodec().getProtocolVersion()),
                null, false));
        server.setGlobalFlag(MinecraftConstants.SERVER_LOGIN_HANDLER_KEY, this::onLogin);
        server.addListener(new ServerAdapter() {
            @Override
            public void sessionAdded(SessionAddedEvent event) {
                // nothing: login is handled in onLogin
            }
        });
        server.bind();
    }

    private void onLogin(Session session) {
        Bridge bridge = new Bridge(config, auth, registries, chunks, gameData, session);
        if (!active.compareAndSet(null, bridge)) {
            session.disconnect("SinkholeMC only supports one player at a time.");
            return;
        }
        bridge.onClosed(() -> active.compareAndSet(bridge, null));
        bridge.start();
    }

    public void stop() {
        Bridge b = active.get();
        if (b != null) {
            b.close();
        }
        if (server != null) {
            server.close();
        }
    }
}
