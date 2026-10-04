#!/usr/bin/env python3
"""
Builds src/main/resources/data/block_map.txt: for every Java block state id of the target Java version, the index of
the matching state in the Bedrock block palette (or -1).

GeyserMC publishes Java -> Bedrock block mappings (blocks.nbt) per Java version (branch feature/26.3 of
GeyserMC/mappings for Java 26.3). If the mapping is for an older Java version than the target, it is carried over by
matching each state with the old state of the same block name and properties; pass the same reports twice when the
mapping already matches the target version.

usage: build_block_map.py <reports/blocks.json of the mappings' version> <reports/blocks.json of the target version> <blocks.nbt> <block_palette.nbt> <out>
  reports come from: java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output gen
"""
import gzip, json, struct, sys

def read_nbt(path):
    data = gzip.decompress(open(path, 'rb').read())
    pos = [0]
    def u(fmt, n):
        v = struct.unpack_from(fmt, data, pos[0]); pos[0] += n; return v[0]
    def string():
        n = u('>H', 2); s = data[pos[0]:pos[0]+n].decode('utf-8'); pos[0] += n; return s
    def payload(t):
        if t == 1: return u('>b', 1)
        if t == 2: return u('>h', 2)
        if t == 3: return u('>i', 4)
        if t == 4: return u('>q', 8)
        if t == 5: return u('>f', 4)
        if t == 6: return u('>d', 8)
        if t == 7: n = u('>i', 4); pos[0] += n; return None
        if t == 8: return string()
        if t == 9:
            it = u('>b', 1); n = u('>i', 4); return [payload(it) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u('>b', 1)
                if tt == 0: return d
                k = string(); d[k] = payload(tt)
        if t == 11: n = u('>i', 4); pos[0] += 4*n; return None
        if t == 12: n = u('>i', 4); pos[0] += 8*n; return None
        raise ValueError(t)
    t = u('>b', 1); string(); return payload(t)

def canon(states):
    return tuple(sorted((k, v) for k, v in states.items()))

def java_states(reports):
    b = json.load(open(reports))
    out = {}
    for name, v in b.items():
        for s in v['states']:
            out[s['id']] = (name, tuple(sorted(s.get('properties', {}).items())))
    return [out[i] for i in range(len(out))]

old_states = java_states(sys.argv[1])
new_states = java_states(sys.argv[2])
mappings = read_nbt(sys.argv[3])['bedrock_mappings']
palette = read_nbt(sys.argv[4])['blocks']
assert len(mappings) == len(old_states), (len(mappings), len(old_states))

index = {}
for i, p in enumerate(palette):
    index[(p['name'], canon(p['states']))] = i

old_to_palette = {}
for (name, props), m in zip(old_states, mappings):
    bname = 'minecraft:' + m.get('bedrock_identifier', name.split(':', 1)[1])
    idx = index.get((bname, canon(m.get('state', {}))), -1)
    old_to_palette[(name, props)] = idx

by_name = {}
for (name, props), idx in old_to_palette.items():
    by_name.setdefault(name, []).append((dict(props), idx))

out, exact, fuzzy, missing = [], 0, 0, []
for name, props in new_states:
    key = (name, props)
    if key in old_to_palette and old_to_palette[key] >= 0:
        out.append(old_to_palette[key]); exact += 1; continue
    best, score = -1, -1
    for oprops, idx in by_name.get(name, []):
        if idx < 0: continue
        p = dict(props)
        shared = [k for k in p if k in oprops]
        if any(p[k] != oprops[k] for k in shared): continue
        s = len(shared)
        if s > score: best, score = idx, s
    if best >= 0:
        out.append(best); fuzzy += 1
    else:
        out.append(-1); missing.append(name)

open(sys.argv[5], 'w').write(','.join(map(str, out)))
reach = set(i for i in out if i >= 0)
print(f"java states: {len(new_states)}  exact: {exact}  fuzzy: {fuzzy}  unmapped: {len(missing)}")
print("unmapped blocks:", sorted(set(missing))[:40], '...' if len(set(missing)) > 40 else '')
print(f"bedrock palette entries reachable from Java: {len(reach)} / {len(palette)}")
