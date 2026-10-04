package dev.sinkhole;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;

/** The Bedrock protocol version we speak. SINKHOLE_CODEC=<protocol number> selects an older one for testing. */
public final class BedrockCodecs {
    private static final BedrockCodec CURRENT = select();

    private BedrockCodecs() {
    }

    public static BedrockCodec current() {
        return CURRENT;
    }

    private static BedrockCodec select() {
        String v = System.getenv("SINKHOLE_CODEC");
        if (v == null || v.isBlank()) {
            return Bedrock_v2193.CODEC;
        }
        try {
            return (BedrockCodec) Class.forName("org.cloudburstmc.protocol.bedrock.codec.v" + v + ".Bedrock_v" + v).getField("CODEC").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown SINKHOLE_CODEC " + v, e);
        }
    }
}
