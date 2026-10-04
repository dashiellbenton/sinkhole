package dev.sinkhole;

import org.jose4j.jws.JsonWebSignature;

import java.security.KeyPair;

/** Minimal ES384 JWT signing for the Bedrock login. */
final class Jwts {
    private Jwts() {
    }

    static String sign(KeyPair key, String publicKeyBase64, String jsonPayload) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(jsonPayload);
        jws.setAlgorithmHeaderValue("ES384");
        jws.setHeader("x5u", publicKeyBase64);
        jws.setKey(key.getPrivate());
        return jws.getCompactSerialization();
    }
}
