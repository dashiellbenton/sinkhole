package dev.sinkhole;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

/** Turns a Bedrock level chunk (sub-chunk list) into a Java level-chunk packet. */
public final class BedrockChunks {
    private static final int GLOBAL_BLOCK_BITS = 16;
    private static final int GLOBAL_BIOME_BITS = 7;

    private final BlockMapper blocks;
    private final int plainsBiome;
    /** Bedrock biome id -> Java biome registry id. */
    private final java.util.Map<Integer, Integer> biomes;

    public BedrockChunks(BlockMapper blocks, int plainsBiome, java.util.Map<Integer, Integer> biomes) {
        this.blocks = blocks;
        this.plainsBiome = plainsBiome;
        this.biomes = biomes;
    }

    private int javaBiome(int bedrockId) {
        return biomes.getOrDefault(bedrockId, plainsBiome);
    }

    /**
     * @param data        the LevelChunk payload (sub-chunks, then biomes/border/block entities which are ignored)
     * @param subChunks   number of sub-chunks in the payload
     * @param javaSections vertical sections of the Java dimension (24 for the overworld)
     * @param hashed      whether the server uses hashed block network ids
     * @param minSection  sub-chunk index (Bedrock) of the lowest Java section
     */
    public ClientboundLevelChunkWithLightPacket translate(int x, int z, ByteBuf data, int subChunks, int javaSections,
                                                          boolean hashed, int minSection) {
        ChunkSection[] sections = newSections(javaSections);
        ByteBuf in = data.duplicate();
        for (int i = 0; i < subChunks && in.isReadable(); i++) {
            int index = i - minSection;
            readSubChunk(in, index >= 0 && index < javaSections ? sections[index] : null);
        }
        readBiomes(in, subChunks, sections, minSection);
        return build(x, z, sections, javaSections);
    }

    public ChunkSection[] newSections(int count) {
        ChunkSection[] sections = new ChunkSection[count];
        for (int i = 0; i < count; i++) {
            // the first argument is the default value: air for blocks, plains for biomes
            sections[i] = new ChunkSection(0, 0, DataPalette.createForBlockState(0, GLOBAL_BLOCK_BITS), DataPalette.createForBiome(plainsBiome, GLOBAL_BIOME_BITS));
        }
        return sections;
    }

    /** Reads one serialized sub-chunk (versions 1, 8, 9) into a Java section; {@code section} may be null to skip. */
    public void readSubChunk(ByteBuf in, ChunkSection section) {
        int version = in.readUnsignedByte();
        int layers = 1;
        if (version == 8 || version == 9) {
            layers = in.readUnsignedByte();
            if (version == 9) {
                in.readByte(); // absolute sub-chunk index; sub-chunks arrive bottom-up so the position is enough
            }
        }
        int[] first = null;
        for (int layer = 0; layer < layers; layer++) {
            int[] ids = readStorage(in);
            if (layer == 0) {
                first = ids;
            }
        }
        if (first == null || section == null) {
            return;
        }
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = 0; by < 16; by++) {
                    int java = first[(bx << 8) | (bz << 4) | by];
                    if (java != 0) {
                        section.setBlock(bx, by, bz, java);
                    }
                }
            }
        }
    }

    public ClientboundLevelChunkWithLightPacket assemble(int x, int z, ChunkSection[] sections) {
        return build(x, z, sections, sections.length);
    }

    public void applyBiomes(ByteBuf in, int count, ChunkSection[] sections, int minSection) {
        readBiomes(in.duplicate(), count, sections, minSection);
    }

    /** Bedrock stores one paletted 16x16x16 biome volume per sub-chunk; Java wants 4x4x4 per section. */
    private void readBiomes(ByteBuf in, int count, ChunkSection[] sections, int minSection) {
        int[] previous = null;
        for (int i = 0; i < count && in.isReadable(); i++) {
            int mark = in.getUnsignedByte(in.readerIndex());
            int[] ids;
            if (mark == 0xFF) {
                in.readByte();
                ids = previous;
            } else {
                ids = readRawStorage(in);
            }
            previous = ids;
            int index = i - minSection;
            if (ids == null || index < 0 || index >= sections.length) {
                continue;
            }
            var data = sections[index].getBiomeData();
            for (int bx = 0; bx < 4; bx++) {
                for (int bz = 0; bz < 4; bz++) {
                    for (int by = 0; by < 4; by++) {
                        data.set(bx, by, bz, javaBiome(ids[((bx * 4) << 8) | ((bz * 4) << 4) | (by * 4)]));
                    }
                }
            }
        }
    }

    /** A block-storage-shaped palette whose entries are plain ids (no translation). */
    private int[] readRawStorage(ByteBuf in) {
        int header = in.readUnsignedByte();
        int bits = header >> 1;
        int[] indices = new int[4096];
        if (bits > 0) {
            int perWord = 32 / bits;
            int words = (4096 + perWord - 1) / perWord;
            int mask = (1 << bits) - 1;
            for (int w = 0; w < words; w++) {
                int word = in.readIntLE();
                for (int j = 0; j < perWord; j++) {
                    int i = w * perWord + j;
                    if (i >= 4096) {
                        break;
                    }
                    indices[i] = (word >>> (j * bits)) & mask;
                }
            }
        }
        int size = bits == 0 ? 1 : readZigZag(in);
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = readZigZag(in);
        }
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            out[i] = palette[Math.min(indices[i], size - 1)];
        }
        return out;
    }

    /** Reads one block storage and returns, per block, the translated Java state id. */
    private int[] readStorage(ByteBuf in) {
        int header = in.readUnsignedByte();
        int bits = header >> 1;
        boolean runtime = (header & 1) == 1;
        int[] indices = new int[4096];
        if (bits > 0) {
            int perWord = 32 / bits;
            int words = (4096 + perWord - 1) / perWord;
            int mask = (1 << bits) - 1;
            for (int w = 0; w < words; w++) {
                int word = in.readIntLE();
                for (int j = 0; j < perWord; j++) {
                    int i = w * perWord + j;
                    if (i >= 4096) {
                        break;
                    }
                    indices[i] = (word >>> (j * bits)) & mask;
                }
            }
        }
        int paletteSize = bits == 0 ? 1 : readZigZag(in);
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = readZigZag(in);
        }
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int rid = palette[Math.min(indices[i], paletteSize - 1)];
            out[i] = blocks.toJava(rid, hashed);
        }
        return out;
    }

    private boolean hashed;

    public BlockMapper blocks() {
        return blocks;
    }

    public void setHashed(boolean hashed) {
        this.hashed = hashed;
    }

    private static int readZigZag(ByteBuf in) {
        int v = 0;
        int shift = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0 && shift < 35);
        return (v >>> 1) ^ -(v & 1);
    }

    private ClientboundLevelChunkWithLightPacket build(int x, int z, ChunkSection[] sections, int count) {
        ByteBuf out = Unpooled.buffer();
        byte[] bytes;
        try {
            for (ChunkSection s : sections) {
                MinecraftTypes.writeChunkSection(out, s);
            }
            bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
        } finally {
            out.release();
        }
        // Full-bright sky light for every section plus the two padding sections above/below.
        BitSet skyMask = new BitSet();
        List<byte[]> sky = new ArrayList<>();
        for (int i = 0; i < count + 2; i++) {
            skyMask.set(i);
            byte[] full = new byte[2048];
            java.util.Arrays.fill(full, (byte) 0xFF);
            sky.add(full);
        }
        LightUpdateData light = new LightUpdateData(skyMask, new BitSet(), new BitSet(), new BitSet(), sky, new ArrayList<>());
        return new ClientboundLevelChunkWithLightPacket(x, z, bytes, new HashMap<HeightmapTypes, long[]>(), new BlockEntityInfo[0], light);
    }
}
