package dev.sinkhole;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/** The Bedrock protocol version we speak. SINKHOLE_CODEC=<protocol number> selects an older one for testing. */
public final class BedrockCodecs {
    private static final BedrockCodec CURRENT;

    private BedrockCodecs() {
    }

    public static BedrockCodec current() {
        return CURRENT;
    }

    /**
     * The stock ItemComponent serializer refuses lists above 1536 items, but current servers send more
     * (about 2000), so the same wire format is re-read with a larger limit.
     */
    private static final BedrockPacketSerializer<ItemComponentPacket> WIDE_ITEM_COMPONENTS = new BedrockPacketSerializer<>() {
        @Override
        public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ItemComponentPacket packet) {
            helper.writeArray(buffer, packet.getItems(), (buf, h, item) -> {
                h.writeString(buf, item.getIdentifier());
                buf.writeShortLE(item.getRuntimeId());
                buf.writeBoolean(item.isComponentBased());
                VarInts.writeInt(buf, item.getVersion().ordinal());
                h.writeTag(buf, item.getComponentData());
            });
        }

        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ItemComponentPacket packet) {
            helper.readArray(buffer, packet.getItems(), (buf, h) -> {
                String identifier = h.readString(buf);
                int runtimeId = buf.readShortLE();
                boolean componentBased = buf.readBoolean();
                ItemVersion version = ItemVersion.from(VarInts.readInt(buf));
                NbtMap data = h.readTag(buf, NbtMap.class);
                return new SimpleItemDefinition(identifier, runtimeId, version, componentBased, data);
            }, 8192);
        }
    };

    static {
        CURRENT = select();
    }

    private static BedrockCodec select() {
        BedrockCodec base = base();
        return base.toBuilder().updateSerializer(ItemComponentPacket.class, WIDE_ITEM_COMPONENTS).build();
    }

    private static BedrockCodec base() {
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
