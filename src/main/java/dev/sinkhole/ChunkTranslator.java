package dev.sinkhole;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-encodes a Java chunk column (a run of 16x16x16 sections) as Bedrock "version 9" sub-chunks plus biome data.
 * Layout reference: Bedrock LevelChunk / sub-chunk network serialization as used by Geyser's chunk code.
 */
public final class ChunkTranslator {
    /** Global palette widths used by MCProtocolLib when it reads a direct (non-paletted) section. */
    private static final int GLOBAL_BLOCK_BITS = 15;
    private static final int GLOBAL_BIOME_BITS = 7;
    private static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    /** Bedrock "plains" biome id; Java biome translation is not implemented yet, so every column uses this. */
    private static final int DEFAULT_BIOME = 1;

    private final BlockMapper blocks;

    public ChunkTranslator(BlockMapper blocks) {
        this.blocks = blocks;
    }

    public static final class Result {
        public final int subChunkCount;
        public final byte[] data;

        Result(int subChunkCount, byte[] data) {
            this.subChunkCount = subChunkCount;
            this.data = data;
        }
    }

    /**
     * @param javaChunkData the chunk data bytes from the Java level-chunk packet
     * @param sectionCount  number of vertical sections in the dimension (24 for the overworld)
     */
    public Result translate(byte[] javaChunkData, int sectionCount) {
        ByteBuf in = Unpooled.wrappedBuffer(javaChunkData);
        ByteBuf out = Unpooled.buffer();
        try {
            for (int y = 0; y < sectionCount; y++) {
                ChunkSection section = MinecraftTypes.readChunkSection(in, GLOBAL_BIOME_BITS, GLOBAL_BLOCK_BITS);
                writeSubChunk(out, section, y);
            }
            // One biome palette per sub-chunk. Bedrock accepts a "copy of previous" marker (0xFF) after the first.
            for (int y = 0; y < sectionCount; y++) {
                if (y == 0) {
                    writeUniformStorage(out, DEFAULT_BIOME);
                } else {
                    out.writeByte(0xFF);
                }
            }
            out.writeByte(0); // border blocks
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return new Result(sectionCount, bytes);
        } finally {
            in.release();
            out.release();
        }
    }

    private void writeSubChunk(ByteBuf out, ChunkSection section, int index) {
        out.writeByte(9); // sub-chunk format version
        out.writeByte(1); // storage layers
        out.writeByte(index); // sub-chunk y index relative to the dimension's minimum section
        // Bedrock orders blocks x, z, y (y fastest); Java sections are read per (x, y, z).
        int[] runtime = new int[4096];
        Map<Integer, Integer> palette = new LinkedHashMap<>();
        List<Integer> paletteList = new ArrayList<>();
        int[] indices = new int[4096];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    int rid = blocks.toBedrock(section.getBlock(x, y, z));
                    runtime[(x << 8) | (z << 4) | y] = rid;
                }
            }
        }
        for (int i = 0; i < 4096; i++) {
            Integer p = palette.get(runtime[i]);
            if (p == null) {
                p = paletteList.size();
                palette.put(runtime[i], p);
                paletteList.add(runtime[i]);
            }
            indices[i] = p;
        }
        writeStorage(out, indices, paletteList);
    }

    private static void writeUniformStorage(ByteBuf out, int value) {
        writeStorage(out, new int[4096], List.of(value));
    }

    private static void writeStorage(ByteBuf out, int[] indices, List<Integer> palette) {
        int needed = 32 - Integer.numberOfLeadingZeros(Math.max(palette.size() - 1, 0));
        int bits = 1;
        for (int w : WIDTHS) {
            bits = w;
            if (w >= needed) {
                break;
            }
        }
        out.writeByte((bits << 1) | 1); // runtime-id (network) palette
        int perWord = 32 / bits;
        int words = (4096 + perWord - 1) / perWord;
        for (int w = 0; w < words; w++) {
            int word = 0;
            for (int j = 0; j < perWord; j++) {
                int i = w * perWord + j;
                if (i >= 4096) {
                    break;
                }
                word |= indices[i] << (j * bits);
            }
            out.writeIntLE(word);
        }
        writeVarInt(out, zigzag(palette.size()));
        for (int rid : palette) {
            writeVarInt(out, zigzag(rid));
        }
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static void writeVarInt(ByteBuf out, int v) {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }
}
