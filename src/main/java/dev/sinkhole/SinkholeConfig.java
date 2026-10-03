package dev.sinkhole;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** config.yml: the UDP port Bedrock connects to, and the Java server to proxy to. */
public final class SinkholeConfig {
    public static final int DEFAULT_PORT = 35653;

    public final String bindAddress;
    public final int port;
    /** "host" or "host:port". Empty when the user hasn't filled it in yet. */
    public final String server;

    private SinkholeConfig(String bindAddress, int port, String server) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.server = server;
    }

    public boolean hasServer() {
        return server != null && !server.isBlank();
    }

    public String serverHost() {
        String s = server.trim();
        int i = s.lastIndexOf(':');
        return i > 0 && s.indexOf(':') == i ? s.substring(0, i) : s;
    }

    public int serverPort() {
        String s = server.trim();
        int i = s.lastIndexOf(':');
        if (i > 0 && s.indexOf(':') == i) {
            try {
                return Integer.parseInt(s.substring(i + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 25565;
    }

    public static void writeDefault(Path file) throws IOException {
        Files.writeString(file, """
                # SinkholeMC configuration
                # Bedrock clients connect to this machine on this UDP port.
                bind-address: "0.0.0.0"
                port: %d

                # The Java Edition server to proxy to, as host or host:port. No default - you must set this.
                server: ""
                """.formatted(DEFAULT_PORT));
    }

    public static SinkholeConfig load(Path file) throws IOException {
        DumperOptions opts = new DumperOptions();
        Map<String, Object> map = new Yaml(opts).load(Files.readString(file));
        if (map == null) {
            map = new LinkedHashMap<>();
        }
        Object port = map.getOrDefault("port", DEFAULT_PORT);
        Object server = map.get("server");
        return new SinkholeConfig(
                String.valueOf(map.getOrDefault("bind-address", "0.0.0.0")),
                port instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(port)),
                server == null ? "" : String.valueOf(server));
    }
}
