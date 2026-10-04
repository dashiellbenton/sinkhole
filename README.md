# SinkholeMC

A single-user proxy that lets you join a **Bedrock Edition server** from **Minecraft: Java Edition**, signed
in with your Microsoft/Xbox account. It is the opposite of Geyser: Geyser lets Bedrock clients join Java
servers, Sinkhole lets a Java client join Bedrock servers.

```
Java client  --TCP-->  SinkholeMC  --RakNet or NetherNet + Xbox login-->  Bedrock server
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

Requirements: Java 21+ (NetherNet bundles native WebRTC for Windows x64, Linux x64, macOS Intel/Apple Silicon). The Java client must be exactly **26.3** (the Java protocol version is not translated).
The Bedrock side speaks protocol 2193 (Bedrock 1.26.50).

## What works

- Xbox device-code sign-in, config generation, quit-on-missing-config
- Both Bedrock transports: **RakNet** (classic) and **NetherNet** (WebRTC; what the newest vanilla Bedrock
  Dedicated Server uses by default). `transport: auto` in `config.yml` asks the server's HTTP endpoint which one
  it speaks. NetherNet is for servers you can reach directly (`host:port`, e.g. a dedicated server on your PC);
  Realms/friend-list/Xbox-signaled worlds are not supported.
- Bedrock 1.26.10+ login (multiplayer token + client data), encryption handshake, and resource-pack
  download (packs are fetched and discarded so servers that require them accept the connection)
- World: chunks (including the newer "request sub-chunks" mode), biomes, block updates, weather, time
- Movement: position/rotation, sneak/sprint/jump/fly input, server corrections, knockback, dimension changes
- Chat and commands, with the gamertag <-> Java name swap in both directions
- Entities: players (tab list too), mobs whose Bedrock name matches a Java entity type, dropped items, names,
  hurt/death animations
- Health, food, experience, game mode, abilities, respawn
- Inventory and hotbar, attacking, swinging, block breaking and placing
- Chests, hoppers, dispensers/droppers, shulker boxes, and inventory clicks through Bedrock
  item-stack requests

## Not done yet

Block entities (sign text, banners, skulls), crafting tables/furnaces/anvils and other special screens (they are
closed again), the crafting grid, creative inventory, entity equipment/effects/riding, particles and sounds,
waterlogging, custom (data-driven) blocks and items, skins (a plain skin is used), Xbox-signaled NetherNet
(Realms, friends' worlds), Java versions other than 26.3. Expect rough edges.

## Testing status - please read

Build and unit tests: `mvn verify` (also run by the GitHub Actions workflow). Integration checks were done with a
headless Java client against:
- a small fake Bedrock server (in `src/test`) serving a flat world: join, chunks, entities, health,
  inventory, chat with name swap, attack/break actions
- [Dragonfly](https://github.com/df-mc/dragonfly), an independent Bedrock server on the same protocol, with
  authentication disabled: login, StartGame, hundreds of chunks, time/attributes, two-way chat

- **vanilla Bedrock Dedicated Server 1.26.52.3** over NetherNet with `online-mode=false`: join, real terrain
  chunks, mobs, items, chat, attributes

**Not verified:** the real Microsoft/Xbox sign-in (it needs a person to enter the code; the Xbox-authenticated
login and the NetherNet identity for real accounts are implemented from the protocol but untested), a real Java
game client, and public servers. The first run against a real server may need fixes.

Developer flag for testing against servers with authentication disabled: `--offline <gamertag>`.
`SINKHOLE_DEBUG=1` prints packet-level logs.

Data files under `src/main/resources/data` come from GeyserMC's mappings (MIT; the `feature/26.3` branch of
`GeyserMC/mappings`, Java 26.3 / Bedrock 1.26.50); see `data/NOTICE`. `tools/build_block_map.py` regenerates
`block_map.txt` when the mappings change.
