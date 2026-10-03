package dev.sinkhole;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final Path CONFIG = Path.of("config.yml");
    private static final Path AUTH = Path.of("auth.json");

    public static void main(String[] args) throws Exception {
        System.out.println("SinkholeMC - Bedrock client -> Java server proxy (single user)");

        boolean freshConfig = !Files.exists(CONFIG);

        AuthService auth = new AuthService(AUTH);
        // Developer flag for testing against offline-mode servers: --offline <name>. Never signs in.
        if (args.length >= 2 && args[0].equals("--offline")) {
            auth.useOffline(args[1]);
        }
        auth.login();
        System.out.println("[Sinkhole] Signed in as " + auth.javaName());

        if (freshConfig) {
            SinkholeConfig.writeDefault(CONFIG);
            die("Created " + CONFIG.toAbsolutePath() + ". Set 'server' (the Java server to proxy to) in it, then start SinkholeMC again.");
        }

        SinkholeConfig config = SinkholeConfig.load(CONFIG);
        if (!config.hasServer()) {
            die("No server to proxy is set. Edit 'server' in " + CONFIG.toAbsolutePath() + " and start SinkholeMC again.");
        }

        ProxyServer server = new ProxyServer(config, auth);
        server.start();
        System.out.println("[Sinkhole] Listening for Bedrock on UDP " + config.bindAddress + ":" + config.port
                + " -> " + config.serverHost() + ":" + config.serverPort());
        System.out.println("[Sinkhole] Add a Bedrock server pointing at this machine, port " + config.port + ".");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    private static void die(String message) {
        System.err.println("[Sinkhole] " + message);
        System.exit(1);
    }
}
