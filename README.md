# PotScript

**Pot-sized programmable servers for Minecraft.** A Server Pot is a block the size of a
flower pot that runs your own programs, written in **PotScript**, a small language built for
the job. Each pot has a terminal, a persistent disk, six redstone outputs and a wifi radio
that reaches every other pot on the server.

> Fabric · Minecraft 26.2 · Java 25

---

## What a pot can do

| | |
| --- | --- |
| **Terminal** | Right-click the pot for a console with a command line and a built-in code editor. |
| **PotScript** | A complete little language: variables, functions, lists, strings, control flow, recursion — compiled to bytecode and run on a stack VM. |
| **Redstone** | Read the signal on any of the six sides, and emit 0–15 out of any of them. |
| **Wifi** | Pots reach each other by hostname, at any distance, across dimensions. Send, broadcast, receive with a timeout. |
| **Disk** | A key/value store that survives world reloads. |
| **Sensors** | Position, dimension, biome, weather, light level, game time, day number. |
| **Players** | List nearby players by name, message them in chat, play note-block tones. |

Programs are **metered**: the VM executes at most 5,000 instructions per game tick and then
yields. An infinite loop is completely harmless — it can never stall the server tick.

## A first program

```
# blink a lamp above the pot, and announce it on the wifi
sethost("blinker")

let on = false
while true {
    on = not on
    if on { rs_set("up", 15) } else { rs_set("up", 0) }
    broadcast(["blink", on, gametime()])
    sleep(20)
}
```

Something a bit more like a computer:

```
print("what should I count to?")
let target = num(read())

if target == nil {
    print("that wasn't a number")
} else {
    let i = 1
    while i <= target {
        print(str(i) + " ...")
        beep(i % 24)
        sleep(10)
        i = i + 1
    }
    say("done counting to " + str(target), 32)
}
```

## Getting a pot

The **Server Pot** is in the *PotScript* creative tab, or:

```
/give @s potscript:server_pot
```

It drops itself when broken — but the program, console and disk stay with the block, so
breaking a pot wipes them.

## Using the terminal

Right-click the pot. You get a console; type `help` for commands.

| Command | Effect |
| --- | --- |
| `run` / `stop` | Start or halt the program |
| `edit` | Open the code editor (**Save** / **Save & Run**) |
| `cat` | Print the source |
| `hostname [name]` | Show or change this pot's wifi name |
| `scan` | List every reachable pot |
| `ls` / `rm <key>` | Inspect and delete disk keys |
| `clear` / `reboot` | Clear the console / full reset |
| `help lang` | PotScript cheat sheet |

While a program is running, anything you type is fed to the script's `read()` — except
`stop`, which halts it.

## Documentation

* **[PotScript language guide](docs/potscript.md)** — the complete reference: syntax,
  semantics, execution model and every builtin, with worked examples.

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

## Project layout

```
src/main/java/com/dimitri/potscript/
├── PotScript.java                    registration: block, item, block entity, creative tab
├── block/ServerPotBlock.java       the block: shape, right-click, redstone source
├── block/ServerPotBlockEntity.java the machine: console, disk, mailbox, VM scheduler
├── net/                            client ↔ server packets and the hostname registry
└── script/                         the PotScript implementation
    ├── Lexer.java                  source  → tokens
    ├── Compiler.java               tokens  → bytecode (single-pass Pratt parser)
    ├── Chunk.java / Op.java        bytecode container and opcodes
    ├── Vm.java                     the metered stack VM
    ├── Builtins.java               the standard library
    └── Values.java / ScriptError.java

src/client/java/com/dimitri/potscript/
└── client/TerminalScreen.java      the terminal UI (owo-lib)
```

## License

[CC0 1.0 Universal](LICENSE) — public domain. Do whatever you like with it.
