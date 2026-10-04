package dev.sinkhole;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.Set;
import org.cloudburstmc.protocol.common.util.VarInts;

/** The Bedrock protocol version we speak. SINKHOLE_CODEC=<protocol number> selects an older one for testing. */
public final class BedrockCodecs {
    private BedrockCodecs() {
    }

    /** Built on first use, after all the static serializers above exist. */
    private static final class Holder {
        static final BedrockCodec CODEC = select();
    }

    public static BedrockCodec current() {
        return Holder.CODEC;
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

    /** Packets Sinkhole actually translates (or needs for the handshake). Everything else is not decoded at all. */
    private static final Set<Class<? extends BedrockPacket>> NEEDED = Set.of(
            NetworkSettingsPacket.class, ServerToClientHandshakePacket.class, ResourcePacksInfoPacket.class,
            ResourcePackStackPacket.class, DisconnectPacket.class, PlayStatusPacket.class, StartGamePacket.class,
            ItemComponentPacket.class, ChunkRadiusUpdatedPacket.class, NetworkChunkPublisherUpdatePacket.class,
            LevelChunkPacket.class, UpdateBlockPacket.class, MovePlayerPacket.class, TextPacket.class,
            AddPlayerPacket.class, AddEntityPacket.class, RemoveEntityPacket.class, MoveEntityAbsolutePacket.class,
            MoveEntityDeltaPacket.class, UpdateAttributesPacket.class, InventoryContentPacket.class,
            InventorySlotPacket.class, SetTimePacket.class,
            // serverbound: what we send must still be encodable
            RequestNetworkSettingsPacket.class, LoginPacket.class, ClientToServerHandshakePacket.class,
            ResourcePackClientResponsePacket.class, ClientCacheStatusPacket.class, RequestChunkRadiusPacket.class,
            SetLocalPlayerAsInitializedPacket.class, PlayerAuthInputPacket.class,
            CommandRequestPacket.class, MobEquipmentPacket.class, InventoryTransactionPacket.class,
            AnimatePacket.class, RespawnPacket.class);

    /** Consumes a packet's bytes without parsing them, so unexpected/changed packets cannot break the connection. */
    private static final BedrockPacketSerializer<BedrockPacket> IGNORE = new BedrockPacketSerializer<>() {
        @Override
        public void serialize(ByteBuf buffer, BedrockCodecHelper helper, BedrockPacket packet) {
        }

        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, BedrockPacket packet) {
            buffer.skipBytes(buffer.readableBytes());
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BedrockCodec select() {
        BedrockCodec base = base();
        BedrockCodec.Builder builder = base.toBuilder().updateSerializer(ItemComponentPacket.class, WIDE_ITEM_COMPONENTS);
        for (int id = 0; id < 512; id++) {
            BedrockPacketDefinition<?> def = base.getPacketDefinition(id);
            if (def == null) {
                continue;
            }
            Class<? extends BedrockPacket> type = def.getFactory().get().getClass();
            // clientbound-only packets we don't translate are skipped; serverbound ones keep their serializer
            if (!NEEDED.contains(type) && def.getRecipient() != PacketRecipient.SERVER) {
                builder.updateSerializer((Class) type, (BedrockPacketSerializer) IGNORE);
            }
        }
        return builder.build();
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
