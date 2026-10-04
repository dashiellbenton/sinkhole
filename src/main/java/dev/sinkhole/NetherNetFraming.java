package dev.sinkhole;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.List;

/**
 * The glue FrameIdCodec is on RakNet: converts between raw transport messages and Bedrock batches. NetherNet messages
 * carry the batch as-is, without RakNet's leading 0xFE frame id.
 */
final class NetherNetFraming extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper batch, List<Object> out) {
        ByteBuf compressed = batch.getCompressed();
        if (compressed == null) {
            throw new IllegalStateException("Bedrock batch was not compressed");
        }
        out.add(compressed.retainedSlice());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf message, List<Object> out) {
        if (message.isReadable()) {
            out.add(BedrockBatchWrapper.newInstance(message.retainedSlice(), null));
        }
    }
}
