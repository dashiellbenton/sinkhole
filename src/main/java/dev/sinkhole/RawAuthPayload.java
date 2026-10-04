package dev.sinkhole;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;

import java.util.List;

/**
 * The login "connection request" JSON written the way real clients of 1.26.10+ write it:
 * {@code {"Certificate":"{\"chain\":[...]}","AuthenticationType":n,"Token":"<multiplayer token>"}}.
 * The stock payload classes can only carry one of chain or token, so {@link BedrockCodecs} serializes this one itself.
 */
final class RawAuthPayload implements AuthPayload {
    private final AuthType type;
    private final String json;

    RawAuthPayload(AuthType type, String token, List<String> chain) {
        this.type = type;
        JsonArray chainJson = new JsonArray();
        if (chain == null || chain.isEmpty()) {
            chainJson.add(""); // unauthenticated clients send a single empty chain entry
        } else {
            chain.forEach(chainJson::add);
        }
        JsonObject certificate = new JsonObject();
        certificate.add("chain", chainJson);
        JsonObject o = new JsonObject();
        o.addProperty("Certificate", certificate.toString());
        o.addProperty("AuthenticationType", type.ordinal() == 0 ? 0 : authTypeId(type));
        o.addProperty("Token", token == null ? "" : token);
        this.json = o.toString();
    }

    private static int authTypeId(AuthType t) {
        return switch (t) {
            case FULL -> 0;
            case GUEST -> 1;
            case SELF_SIGNED -> 2;
            default -> -1;
        };
    }

    String json() {
        return json;
    }

    @Override
    public AuthType getAuthType() {
        return type;
    }
}
