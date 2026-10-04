package dev.sinkhole;


import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final Path CONFIG = Path.of("config.yml");
    private static final Path AUTH = Path.of("auth.json");

    public static void main(String[] args) throws Exception {
        System.out.println("SinkholeMC - Java client -> Bedrock server proxy (single user)");

        boolean freshConfig = !Files.exists(CONFIG);

        AuthService auth = new AuthService(AUTH, BedrockCodecs.current().getMinecraftVersion());
        // Developer flag for testing against Bedrock servers with online-mode off: --offline <gamertag>.
        if (args.length >= 2 && args[0].equals("--offline")) {
            auth.useOffline(args[1]);
        }
        auth.login();
        System.out.println("[Sinkhole] Signed in as " + auth.gamertag());

        if (freshConfig) {
            SinkholeConfig.writeDefault(CONFIG);
            die("Created " + CONFIG.toAbsolutePath() + ". Set 'server' (the Bedrock server to proxy to) in it, then start SinkholeMC again.");
        }

        SinkholeConfig config = SinkholeConfig.load(CONFIG);
        if (!config.hasServer()) {
            die("No server to proxy is set. Edit 'server' in " + CONFIG.toAbsolutePath() + " and start SinkholeMC again.");
        }

        Registries registries = new Registries();
        BlockMapper blocks = new BlockMapper();
        BedrockChunks chunks = new BedrockChunks(blocks, registries.idOf("minecraft:worldgen/biome", "minecraft:plains"));
        JavaFrontend frontend = new JavaFrontend(config, auth, registries, chunks);
        frontend.start();
        System.out.println("[Sinkhole] Join with Minecraft Java at " + config.bindAddress + ":" + config.port
                + "  ->  Bedrock server " + config.serverHost() + ":" + config.serverPort());
        Runtime.getRuntime().addShutdownHook(new Thread(frontend::stop));
    }

    private static void die(String message) {
        System.err.println("[Sinkhole] " + message);
        System.exit(1);
    }
}
