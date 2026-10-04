package dev.sinkhole;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.bedrock.model.MinecraftCertificateChain;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Microsoft/Xbox sign-in via the device-code flow (microsoft.com/link). The result is the identity the proxy
 * uses to log in to the Bedrock server. Tokens are cached in auth.json.
 */
public final class AuthService {
    /** What the Bedrock server needs from us to log in. */
    public record Identity(String gamertag, String xuid, UUID uuid, List<String> chain, AuthType authType, KeyPair key) {
    }

    private final Path cacheFile;
    private final String gameVersion;
    private final HttpClient http = MinecraftAuth.createHttpClient();
    private BedrockAuthManager manager;
    private String offlineName;

    public AuthService(Path cacheFile, String gameVersion) {
        this.cacheFile = cacheFile;
        this.gameVersion = gameVersion;
    }

    /** Developer-only: skip Microsoft sign-in and join offline-mode (xbox-auth disabled) Bedrock servers. */
    public void useOffline(String gamertag) {
        this.offlineName = gamertag;
    }

    public boolean isOffline() {
        return offlineName != null;
    }

    /** Uses the cached login if there is one, otherwise prompts with a microsoft.com/link code. */
    public void login() throws Exception {
        if (isOffline()) {
            return;
        }
        if (Files.exists(cacheFile)) {
            try {
                manager = BedrockAuthManager.fromJson(http, gameVersion, new Gson().fromJson(Files.readString(cacheFile), JsonObject.class));
                manager.getMinecraftCertificateChain().refreshIfExpired();
                save();
                return;
            } catch (Exception e) {
                System.out.println("[Sinkhole] Saved login is no longer valid (" + e.getMessage() + "), signing in again.");
            }
        }
        manager = BedrockAuthManager.create(http, gameVersion).login((client, config) -> new DeviceCodeMsaAuthService(client, config, code -> {
            System.out.println();
            System.out.println("[Sinkhole] To sign in, open " + code.getVerificationUri() + " and enter the code: " + code.getUserCode());
            System.out.println();
        }));
        manager.getMinecraftCertificateChain().refreshIfExpired();
        save();
    }

    private void save() throws IOException {
        Files.writeString(cacheFile, new Gson().toJson(BedrockAuthManager.toJson(manager)));
    }

    /** The Xbox gamertag (or the offline test name). */
    public String gamertag() {
        return isOffline() ? offlineName : manager.getMinecraftCertificateChain().getCached().getIdentityDisplayName();
    }

    public Identity identity() throws IOException {
        if (isOffline()) {
            return offlineIdentity();
        }
        MinecraftCertificateChain chain = manager.getMinecraftCertificateChain().getUpToDate();
        save();
        return new Identity(chain.getIdentityDisplayName(), chain.getIdentityXuid(), chain.getIdentityUuid(),
                List.of(chain.getIdentityJwt(), chain.getMojangJwt()), AuthType.FULL, manager.getSessionKeyPair());
    }

    private Identity offlineIdentity() {
        try {
            KeyPair kp = EncryptionUtils.createKeyPair();
            String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            long now = System.currentTimeMillis() / 1000;
            UUID uuid = UUID.nameUUIDFromBytes(("Sinkhole:" + offlineName).getBytes());
            String jwt = Jwts.sign(kp, pub, "{\"identityPublicKey\":\"" + pub + "\",\"iss\":\"self\",\"randomNonce\":" + System.nanoTime()
                    + ",\"iat\":" + now + ",\"nbf\":" + (now - 60) + ",\"exp\":" + (now + 86400)
                    + ",\"extraData\":{\"displayName\":\"" + offlineName + "\",\"identity\":\"" + uuid + "\",\"XUID\":\"0\",\"titleId\":\"896928775\"}}");
            return new Identity(offlineName, "0", uuid, List.of(jwt), AuthType.SELF_SIGNED, kp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
