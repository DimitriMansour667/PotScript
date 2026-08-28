# PotScript

**Pot-sized programmable servers for Minecraft.** A Server Pot is a block the size of a
flower pot that runs your own programs, written in **PotScript**, a small language built for
the job. Each pot has a terminal, a persistent disk, six redstone outputs and a wifi radio
that reaches every other pot on the server.

> Fabric · Minecraft 26.2 · Java 25

---

## Getting a pot

The **Server Pot** is in the *PotScript* creative tab, or:

```
/give @s potscript:server_pot
```

It drops itself when broken — but the program, console and disk stay with the block, so
breaking a pot wipes them.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer for Minecraft 26.2.
2. Drop these into `mods/`:
   * this mod's jar
   * [Fabric API](https://modrinth.com/mod/fabric-api) 0.158.0+26.2 or newer
   * [owo-lib](https://modrinth.com/mod/owo-lib) 0.13.1+26.2 or newer
3. Java 25 or newer is required.

The mod works in single-player and on dedicated servers; it must be installed on both the
client and the server.

## Building from source

```bash
git clone https://github.com/DimitriMansour667/PotScript.git
cd PotScript
./gradlew build          # jar lands in build/libs/
./gradlew runClient      # launch a dev client
```

The toolchain is pinned in `mise.toml` (Temurin 25, Gradle 9.5.1); if you use
[mise](https://mise.jdx.dev/), `mise install` sets it up.

## Documentation

The full PotScript reference — quickstart, language guide, execution model, standard
library, errors/gotchas and worked examples — lives on the
**[project wiki](https://github.com/DimitriMansour667/PotScript/wiki)**.

## License

[CC0 1.0 Universal](LICENSE) — public domain. Do whatever you like with it.
