# SinkholeMC

A single-user proxy that lets the **Bedrock** game join a **Java Edition** server, signed in with your
Microsoft/Xbox account. (Think "Geyser, but you run it yourself with your own account".)

## Use

```
mvn package
java -jar target/SinkholeMC.jar
```

1. First launch prints a `microsoft.com/link` code. Enter it to sign in (cached in `auth.json`).
2. A `config.yml` is generated (`port` defaults to **35653**, `server` has no default) and SinkholeMC exits.
3. Set `server:` to the Java server (`host` or `host:port`) and start it again.
   If `config.yml` or `server` is missing, SinkholeMC prints a message and quits.
4. In Bedrock, add a server pointing at this machine on the configured port (UDP).

Only one player is proxied at a time; a second connection is refused. The Java username of the signed-in
account replaces the Bedrock gamertag (chat/commands you send, and the identity used to log in).

## Status - read this

This is a **foundation**, not a finished Geyser replacement. Implemented and compiling:

- Device-code Microsoft login + token caching, config/exit behaviour above
- RakNet/Bedrock listener (protocol 1.26.x codec `Bedrock_v2193`), login handshake, single-user lock
- Java client login with your account (MCProtocolLib 26.1), keep-alive handled by the library
- Chat both directions, Java username substitution, disconnect messages, position sync, basic movement

- Java block state -> Bedrock runtime id mapping from GeyserMC's published mappings (all 32,366 Java 26.2
  states resolve against the Bedrock 1.26.50 palette; checked at startup)
- Chunk translation (blocks only) into Bedrock sub-chunks, bedrock item definitions in StartGame

**Untested against a real Bedrock client** - the chunk encoder and join sequence are written to match
Geyser's behaviour but have only been exercised with synthetic data. Expect to debug the first join.

**Not implemented yet**: Java biome -> Bedrock biome mapping (every column is "plains"), block
updates, block entities, entities, inventories/items/creative menu, crafting, biome/entity-identifier
packets the client may require, combat, effects, forms, skins, resource packs, non-overworld dimensions.
So even once a world loads it is not yet playable on a real server.

## About "Bedrock PvP / cooldown" and bans

Sinkhole does **not** make Java servers apply Bedrock combat rules. The Java server sees a normal Java
client, so Java combat (attack cooldown etc.) applies, exactly as with Geyser. Sending attacks faster than
Java's cooldown allows, or otherwise faking client behaviour to get Bedrock-style PvP on a Java server, is
what anti-cheats flag and ban for, so it is deliberately not part of this project. Public servers may also
prohibit proxies/modified clients; check their rules.
