package dev.sinkhole;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Microsoft/Xbox sign-in via the device-code flow (microsoft.com/link), cached in auth.json. */
public final class AuthService {
    private final Path cacheFile;
    private final HttpClient http = MinecraftAuth.createHttpClient();
    private JavaAuthManager manager;

    public AuthService(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    public boolean hasCachedLogin() {
        return Files.exists(cacheFile);
    }

    /** Uses the cached login if there is one, otherwise prompts with a microsoft.com/link code. */
    public void login() throws Exception {
        if (hasCachedLogin()) {
            try {
                manager = JavaAuthManager.fromJson(http, new Gson().fromJson(Files.readString(cacheFile), JsonObject.class));
                manager.getMinecraftToken().refreshIfExpired();
                manager.getMinecraftProfile().refreshIfExpired();
                save();
                return;
            } catch (Exception e) {
                System.out.println("[Sinkhole] Saved login is no longer valid (" + e.getMessage() + "), signing in again.");
            }
        }
        manager = JavaAuthManager.create(http).login((client, config) -> new DeviceCodeMsaAuthService(client, config, code -> {
            System.out.println();
            System.out.println("[Sinkhole] To sign in, open " + code.getVerificationUri() + " and enter the code: " + code.getUserCode());
            System.out.println();
        }));
        save();
    }

    private void save() throws IOException {
        Files.writeString(cacheFile, new Gson().toJson(JavaAuthManager.toJson(manager)));
    }

    public String javaName() {
        return manager.getMinecraftProfile().getCached().getName();
    }

    public java.util.UUID javaUuid() {
        return manager.getMinecraftProfile().getCached().getId();
    }

    public String accessToken() throws IOException {
        String t = manager.getMinecraftToken().getUpToDate().getToken();
        save();
        return t;
    }
}
