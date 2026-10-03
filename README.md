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

**Not implemented yet** (the bulk of Geyser): chunk/block translation (needs the Java<->Bedrock block
palettes), entities, inventories/items, combat, effects, forms, skins, resource packs, and so on.
Until chunk translation exists the Bedrock client will not show a world.

## About "Bedrock PvP / cooldown" and bans

Sinkhole does **not** make Java servers apply Bedrock combat rules. The Java server sees a normal Java
client, so Java combat (attack cooldown etc.) applies, exactly as with Geyser. Sending attacks faster than
Java's cooldown allows, or otherwise faking client behaviour to get Bedrock-style PvP on a Java server, is
what anti-cheats flag and ban for, so it is deliberately not part of this project. Public servers may also
prohibit proxies/modified clients; check their rules.
