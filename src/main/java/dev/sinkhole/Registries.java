package dev.sinkhole;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The data-driven registries a Java client needs in its configuration phase. There is no Java server to ask, so the
 * vanilla set shipped inside MCProtocolLib (networkCodec.nbt / networkTags.nbt) is replayed to the client.
 */
public final class Registries {
    private final NbtMap codec;
    private final NbtMap tags;

    public Registries() throws IOException {
        this.codec = read("/networkCodec.nbt");
        this.tags = read("/networkTags.nbt");
    }

    private static NbtMap read(String resource) throws IOException {
        InputStream s = Registries.class.getResourceAsStream(resource);
        if (s == null) {
            throw new IOException("Missing " + resource + " (MCProtocolLib)");
        }
        try (NBTInputStream in = new NBTInputStream(new DataInputStream(new GZIPInputStream(s)), 1L << 30)) {
            return (NbtMap) in.readTag(1024);
        }
    }

    /** One RegistryData packet per registry. */
    public List<ClientboundRegistryDataPacket> registryPackets() {
        List<ClientboundRegistryDataPacket> out = new ArrayList<>();
        for (String registry : codec.keySet()) {
            NbtMap reg = codec.getCompound(registry);
            List<RegistryEntry> entries = new ArrayList<>();
            for (NbtMap e : reg.getList("value", NbtType.COMPOUND)) {
                NbtMap element = e.getCompound("element");
                entries.add(new RegistryEntry(Key.key(e.getString("name")), element));
            }
            out.add(new ClientboundRegistryDataPacket(Key.key(registry), entries));
        }
        return out;
    }

    public ClientboundUpdateTagsPacket tagsPacket() {
        Map<Key, Map<Key, int[]>> all = new HashMap<>();
        for (String registry : tags.keySet()) {
            Map<Key, int[]> perTag = new HashMap<>();
            NbtMap reg = tags.getCompound(registry);
            for (String tag : reg.keySet()) {
                perTag.put(Key.key(tag), reg.getIntArray(tag) != null && reg.getIntArray(tag).length > 0 ? reg.getIntArray(tag) : toInts(reg.get(tag)));
            }
            all.put(Key.key(registry), perTag);
        }
        return new ClientboundUpdateTagsPacket(all);
    }

    private static int[] toInts(Object o) {
        if (o instanceof int[] a) {
            return a;
        }
        if (o instanceof List<?> l) {
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = ((Number) l.get(i)).intValue();
            }
            return a;
        }
        return new int[0];
    }

    /** Network id of an entry of a registry (its position in the packet), or 0. */
    public int idOf(String registry, String name) {
        NbtMap reg = codec.getCompound(registry);
        for (NbtMap e : reg.getList("value", NbtType.COMPOUND)) {
            if (e.getString("name").equals(name)) {
                return e.getInt("id");
            }
        }
        return 0;
    }
}
