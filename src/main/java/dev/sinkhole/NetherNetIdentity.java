package dev.sinkhole;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jose4j.jws.JsonWebSignature;

import java.security.KeyPair;
import java.util.Base64;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity assertion NetherNet servers expect in the SDP offer ({@code a=identity:...}): the client signs its
 * DTLS fingerprints with its Minecraft key and presents a token that carries the matching public key ("cpk" claim).
 * For real accounts the token comes from the Minecraft authorization service; for the offline test mode a
 * self-signed token is generated.
 */
final class NetherNetIdentity {
    /** The issuer the Minecraft authorization service signs tokens as. */
    static final String DEFAULT_DOMAIN = "https://authorization.franchise.minecraft-services.net/";

    private static final Pattern FINGERPRINT = Pattern.compile("a=fingerprint:(\\S+) (\\S+)");

    private NetherNetIdentity() {
    }

    /** @param token the service token, or null to use a self-signed one */
    static UnaryOperator<String> assertion(KeyPair key, String token, String domain) {
        return sdp -> {
            try {
                Matcher m = FINGERPRINT.matcher(sdp);
                if (!m.find()) {
                    throw new IllegalStateException("the offer has no DTLS fingerprint");
                }
                String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());

                // detached ES384 signature over {"fingerprint":[{"algorithm":..,"digest":..}]}
                JsonObject fp = new JsonObject();
                fp.addProperty("algorithm", m.group(1));
                fp.addProperty("digest", m.group(2));
                JsonArray list = new JsonArray();
                list.add(fp);
                JsonObject payload = new JsonObject();
                payload.add("fingerprint", list);
                JsonWebSignature jws = new JsonWebSignature();
                jws.setPayload(payload.toString());
                jws.setAlgorithmHeaderValue("ES384");
                jws.setKey(key.getPrivate());
                String[] parts = jws.getCompactSerialization().split("\\.");
                String detached = parts[0] + ".." + parts[2];

                String jwt = token != null ? token : selfSignedToken(key, publicKey);

                JsonObject assertion = new JsonObject();
                assertion.addProperty("fingerprints", detached);
                assertion.addProperty("token", jwt);
                JsonObject idp = new JsonObject();
                idp.addProperty("domain", domain);
                idp.addProperty("protocol", "default");
                JsonObject identity = new JsonObject();
                identity.addProperty("assertion", assertion.toString());
                identity.add("idp", idp);
                String attribute = "a=identity:" + Base64.getEncoder().encodeToString(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

                // session-level attribute: before the first media section
                int media = sdp.indexOf("\nm=");
                int at = media < 0 ? sdp.length() : media + 1;
                String sep = sdp.contains("\r\n") ? "\r\n" : "\n";
                return sdp.substring(0, at) + attribute + sep + sdp.substring(at);
            } catch (Exception e) {
                throw new IllegalStateException("Could not build the NetherNet identity assertion: " + e.getMessage(), e);
            }
        };
    }

    private static String selfSignedToken(KeyPair key, String publicKey) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        JsonObject claims = new JsonObject();
        claims.addProperty("exp", now + 60);
        claims.addProperty("iat", now);
        claims.addProperty("cpk", publicKey);
        return Jwts.sign(key, publicKey, claims.toString());
    }
}
