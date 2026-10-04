# SinkholeMC

A single-user proxy that lets you join a **Bedrock Edition server** from **Minecraft: Java Edition**, signed
in with your Microsoft/Xbox account. It is the opposite of Geyser: Geyser lets Bedrock clients join Java
servers, Sinkhole lets a Java client join Bedrock servers.

```
Java client  --TCP-->  SinkholeMC  --RakNet/UDP + Xbox login-->  Bedrock server
```

Because the real server is a Bedrock server, Bedrock gameplay rules (PvP, attack cooldown, movement,
anti-cheat) are applied by that server. Sinkhole only translates packets; it does not change game mechanics.

## Use

```
mvn package
java -jar target/SinkholeMC.jar
```

1. First launch prints a `microsoft.com/link` code. Enter it to sign in (cached in `auth.json`).
2. `config.yml` is generated (`port` defaults to **35653**, `server` has no default) and SinkholeMC exits.
3. Set `server:` to the Bedrock server (`host` or `host:port`, port defaults to 19132) and start it again.
   If `config.yml` or `server` is missing, SinkholeMC prints a message and quits.
4. In Minecraft **Java Edition 26.3**, add a server `localhost:35653` and join.

One player at a time; a second connection is refused. The Xbox gamertag the Bedrock server sees is swapped
for the Java username you joined with, in chat, commands and player names (both directions).

Requirements: Java 21+. The Java client must be exactly **26.3** (the Java protocol version is not translated).
The Bedrock side speaks protocol 2193 (Bedrock 1.26.50).

## What works

- Xbox device-code sign-in, config generation, quit-on-missing-config
- Bedrock login/encryption handshake over RakNet (negative RakNet GUID, as real clients use)
- Chunks and block updates (Bedrock -> Java blocks via GeyserMC's published mappings, checked at startup)
- Position/rotation, sneak/sprint/jump input, chat, commands
- Entities (players and mobs whose Bedrock name matches a Java entity type), health/food
- Inventory/hotbar display and selection, attacking, swinging, block breaking and placing
- Death/respawn request

## Not done yet

Biomes (all plains), block entities (chests/signs), item drops, entity metadata/equipment/effects,
containers other than your own inventory, creative inventory, crafting, particles/sounds, weather and
day/night, dimension changes, sub-chunk request mode, resource packs (the proxy claims to have them),
skins (a plain skin is sent), Java versions other than 26.3. Expect rough edges.

## Testing status - please read

Verified here with a headless Java client against:
- a small fake Bedrock server (in `src/test`) serving a flat world: join, chunks, entities, health,
  inventory, chat with name swap, attack/break actions
- [Dragonfly](https://github.com/df-mc/dragonfly), an independent Bedrock server on the same protocol, with
  authentication disabled: login, StartGame, hundreds of chunks, time/attributes, two-way chat

**Not verified:** the real Microsoft/Xbox sign-in (needs a human to enter the code), a real Java game
client, vanilla Bedrock Dedicated Server (its newest builds only accept the NetherNet transport, which
Sinkhole does not implement; older builds that use RakNet were not accepted by an offline test login), and
public servers. The first run against a real server may need fixes.

Developer flag for testing against servers with authentication disabled: `--offline <gamertag>`.
`SINKHOLE_DEBUG=1` prints packet-level logs.

Data files under `src/main/resources/data` come from GeyserMC's mappings (MIT; the `feature/26.3` branch of
`GeyserMC/mappings`, Java 26.3 / Bedrock 1.26.50); see `data/NOTICE`. `tools/build_block_map.py` regenerates
`block_map.txt` when the mappings change.
