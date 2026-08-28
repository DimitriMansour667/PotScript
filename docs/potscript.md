# PotScript

PotScript is the scripting language that runs inside a **Server Pot**, the flower-pot-sized
computer added by the PotScript mod. A pot holds one program, a 200-line console, a persistent
key/value disk, six redstone outputs and a wifi mailbox. The program runs on the server
thread in a metered bytecode VM, so it can loop forever, sleep, wait for messages and drive
redstone without ever stalling the game.

This document covers the whole language and every builtin.

---

## 1. Quickstart

1. Place a **Server Pot** (creative tab *PotScript*, or break/craft the block item).
2. Right-click it to open the terminal.
3. Type `edit` (or click **Edit**) to open the code editor.
4. Paste a program and click **Save & Run**.

```
# blink a redstone lamp above the pot, once a second
let on = false
while true {
    on = not on
    if on { rs_set("up", 15) } else { rs_set("up", 0) }
    print("lamp is " + str(on))
    sleep(20)
}
```

Type `stop` in the console to halt it. Type `help` for shell commands, `help lang` for the
built-in cheat sheet.

---

## 2. The terminal

The terminal has two views: a **console** with a command line, and a **code editor**.

* **Console** — scrollback of the last 200 lines. White lines are echoes of what you typed,
  yellow lines are status (`[running]`, `[saved]`, `[net] ...`), red lines are errors, green
  is program output.
* **Editor** — a plain text area with **Save**, **Save & Run** and **Console** buttons.
  Programs are capped at 100,000 characters.

The terminal only stays connected while you are within **16 blocks** of the pot.

### Shell commands (when no program is running)

| Command | Effect |
| --- | --- |
| `help` | List commands |
| `help lang` | Print the PotScript cheat sheet |
| `run` | Compile and run the stored program |
| `stop` | (no-op when idle) |
| `edit` | Open the code editor |
| `cat` | Print the program source |
| `hostname` | Show this pot's hostname |
| `hostname <name>` | Rename the pot on the wifi network |
| `scan` | List every reachable host |
| `ls` | List disk keys and abbreviated values |
| `rm <key>` | Delete a disk key |
| `clear` | Clear the console |
| `reboot` | Stop the program, reset redstone, clear console |

### While a program is running

Every line you type is queued and handed to the next `read()` call — **except**:

* `stop` halts the program instead of being delivered.
* `edit` is intercepted by the client and opens the editor; it never reaches the pot.

The input queue holds 16 lines; extra lines are rejected with `[error] input queue full`.
Lines are stripped of surrounding whitespace, truncated to 256 characters, and empty lines
are dropped.

---

## 3. Lexical structure

### Comments

```
# everything after a hash, to the end of the line
```

There are no block comments.

### Statement terminators

A statement ends at a **newline** or a **semicolon** (`;`). Newlines are *suppressed* inside
parentheses and brackets, so calls and list literals may span lines:

```
let grid = [
    1, 2, 3,
    4, 5, 6
]
print("a",
      "b")
```

Braces do **not** suppress newlines — a `{ }` block is a sequence of statements, one per line.

```
let a = 1; let b = 2      # semicolons work too
```

### Identifiers

Letters (`a`–`z`, `A`–`Z`), `_`, and digits after the first character. Case sensitive.

### Keywords

```
let  fn  if  else  while  return  break  continue
and  or  not  true  false  nil
```

Builtin function names are *not* reserved — `let print = 3` is legal and shadows the builtin
for the rest of the program.

### Numbers

Decimal only, always 64-bit floating point: `42`, `3.14`, `0.5`. There is no hex, no
exponent notation, and no leading-dot form (`.5` is invalid — write `0.5`). A trailing dot is
not part of the number (`5.` lexes as `5` followed by an error).

### Strings

Double quotes only. Escapes: `\n`, `\t`, `\"`, `\\`. Any other escape is a compile error, and
a raw newline inside a string is an "unterminated string" error.

```
print("tab\there\nnew line")
```

### Operators and punctuation

```
+  -  *  /  %        ( )  [ ]  { }  ,
=  ==  !=  <  <=  >  >=
```

A bare `!` is a compile error: use `not` for negation and `!=` for inequality.

---

## 4. Values and types

Six types, reported by `type(x)`:

| Type name | Values |
| --- | --- |
| `nil` | `nil` |
| `bool` | `true`, `false` |
| `number` | double-precision float |
| `string` | UTF-16 text |
| `list` | mutable, heterogeneous, 0-indexed |
| `function` | user functions and builtins |

### Truthiness

Only `nil` and `false` are falsy. **`0` and `""` are truthy.**

```
if 0 { print("this prints") }
```

### Number printing

Whole numbers below 1e15 print without a decimal point; everything else prints as a Java
double.

```
print(10 / 4)     # 2.5
print(10 / 5)     # 2
```

### Equality

`==` and `!=` compare by value. Lists compare element-by-element (deeply). Types never
coerce: `1 == "1"` is `false`.

```
print([1, [2]] == [1, [2]])   # true
```

### Lists

Lists are reference values: assigning one to another variable or passing one to a function
does not copy it.

```
let a = [1, 2]
let b = a
push(b, 3)
print(a)          # [1, 2, 3]
```

The only exception is `send`/`broadcast`, which deep-copy the payload so sender and receiver
never share mutable state.

---

## 5. Variables

`let` declares. At the top level it creates a **global**; inside any `{ }` block it creates a
**local**.

```
let count = 0        # global
let empty            # declared, initialised to nil
{
    let count = 99   # a distinct local, shadows the global
    print(count)     # 99
}
print(count)         # 0
```

Rules:

* Assigning to a name that was never declared is a **runtime error**
  (`undefined variable 'x'`) — always `let` first.
* Reading an undeclared name is the same error.
* Declaring the same local twice in one scope is a **compile error**. Re-`let`ing a global is
  allowed and simply overwrites it.
* Locals are limited to 256 per function.

Assignment is an **expression** that yields the assigned value, so `(x = 5)` and
`print(x = 5)` both work — though the plain statement form is what you normally want.

---

## 6. Operators

### Arithmetic

| Operator | Operands | Notes |
| --- | --- | --- |
| `+` | number+number | addition |
| `+` | anything with a string | concatenation — the other side is stringified |
| `+` | list+list | returns a **new** joined list |
| `-` `*` `/` `%` | numbers only | `/` and `%` by zero are runtime errors |
| `-x` | number | negation |

```
print("count: " + 3)    # "count: 3"
print([1] + [2])        # [1, 2]
print(7 % 3)            # 1
print(-7 % 3)           # -1   (sign follows the left operand)
```

Adding two values that are neither of the above (e.g. a bool and a number) is a runtime
error.

### Comparison

`<`, `<=`, `>`, `>=` accept **numbers only** — comparing strings or bools raises
`expected a number, got ...`. There is no lexicographic string comparison.

Comparisons do not chain usefully: `1 < 2 < 3` parses as `(1 < 2) < 3` and then fails at
runtime because `true` is not a number.

### Logic

`and`, `or` short-circuit and return **one of their operands**, not a coerced bool:

```
let name = load("name") or "unnamed"    # nil-coalescing idiom
let ok = has_msg() and recv()           # only receives if a message is waiting
```

`not x` always returns a bool.

### Indexing

`x[i]` reads from a **list** or a **string**; `x[i] = v` writes into a **list** only.

* Indices must be numbers; fractional indices are floored.
* Negative indices count from the end: `l[-1]` is the last element.
* Out-of-range indices are runtime errors — there is no silent `nil`.
* Indexing a string yields a one-character string.

```
let word = "pot"
print(word[0])      # "p"
print(word[-1])     # "t"
```

### Precedence

Lowest to highest:

```
=                       (assignment)
or
and
==  !=
<  <=  >  >=
+  -
*  /  %
-x  not x               (unary)
f(...)   x[...]         (call, index)
```

All binary operators are left-associative. Parentheses group as usual.

---

## 7. Control flow

### if / else

```
if light() > 10 {
    print("day")
} else if light() > 4 {
    print("dusk")
} else {
    print("night")
}
```

Braces are mandatory — there are no brace-less bodies. The `else` may sit on its own line
after the closing brace.

### while

```
let i = 0
while i < 10 {
    if i == 3 { i = i + 1; continue }
    if i == 8 { break }
    print(i)
    i = i + 1
}
```

`while` is the only loop. `break` and `continue` are compile errors outside a loop; both
correctly discard the locals of the scopes they exit.

There is no `for` loop — iterate with `while`, or with `range`:

```
let xs = range(5)          # [0, 1, 2, 3, 4]
let i = 0
while i < len(xs) {
    print(xs[i])
    i = i + 1
}
```

### Blocks

A bare `{ }` is a statement and introduces a scope:

```
{
    let temp = expensive()
    print(temp)
}   # temp is gone here
```

---

## 8. Functions

```
fn add(a, b) {
    return a + b
}

fn fib(n) {
    if n < 2 { return n }
    return fib(n - 1) + fib(n - 2)
}

print(add(2, 3))    # 5
```

Rules and limits:

* **Top level only.** Declaring `fn` inside a block, a loop or another function is a compile
  error (`functions must be declared at top level`).
* **No closures.** A function body sees its own parameters and locals, plus globals. It
  cannot capture a local from an enclosing scope.
* **Arity is exact.** Calling with the wrong number of arguments is a runtime error. Maximum
  16 parameters and 16 arguments.
* A function that falls off the end, or uses bare `return`, returns `nil`.
* **Recursion works**, up to 64 call frames deep (`call stack overflow` past that).
* Functions are ordinary values — store them in variables and lists and call them later:

  ```
  let ops = [add, fib]
  print(ops[0](1, 2))
  ```
* A `fn` declaration takes effect when execution reaches it. Since top-level code runs top to
  bottom, mutual recursion is fine as long as both functions are declared before the first
  call executes.

---

## 9. Execution model

### Metered ticking

The VM executes at most **5,000 instructions per game tick** (~100,000/second) and then
yields until the next tick. An infinite loop is therefore harmless — it just runs slowly and
never blocks the server. Consequences:

* A tight `while true { }` with no `sleep` burns the whole budget every tick and starves
  nothing but itself.
* Timing-sensitive code should use `sleep`, which parks the VM at zero cost.

### Blocking calls

Three builtins can park the VM. While parked it consumes no budget; the scheduler resumes it.

| Call | Wakes when |
| --- | --- |
| `sleep(ticks)` | the given number of game ticks have elapsed |
| `read()` | a line is typed into the terminal |
| `recv()` / `recv(timeout)` | a message arrives, or the timeout expires (returning `nil`) |

`sleep(0)` (or any non-positive value) returns immediately without parking.

### Lifecycle and persistence

* `run` (or **Save & Run**) compiles the source. Compile errors print as `[compile error]`
  and nothing runs.
* A finished program prints `[program finished]`; a runtime error prints `[error] line N: ...`
  and stops.
* The program source, hostname, console scrollback, disk contents and redstone output levels
  are all saved with the block. **Break the pot and they are gone** — there is no item
  persistence.
* If a program was running when the chunk unloaded, it **restarts from the beginning** when
  the chunk reloads. VM state (variables, position in the program) is *not* saved — use the
  disk to keep anything that must survive a reload.

### Runtime limits

| Limit | Value |
| --- | --- |
| Instructions per tick | 5,000 |
| Value stack | 1,024 slots |
| Call frames | 64 |
| Locals per function | 256 |
| Parameters / arguments | 16 |
| List literal elements | 1,024 |
| Longest string | 50,000 chars |
| Longest list | 10,000 elements |
| Program source | 100,000 chars |
| Console scrollback | 200 lines, 256 cols |
| Mailbox | 64 messages |
| Terminal input queue | 16 lines |
| Disk | 256 keys, 64-char keys, 4,096-char values |

Exceeding a runtime limit raises a normal script error (`string too long`, `list too long`,
`stack overflow`, …).

---

## 10. Standard library

Every builtin lives in the global scope. Argument counts are enforced; `[x]` marks an
optional argument.

### Console

| Function | Returns | Description |
| --- | --- | --- |
| `print(...)` | `nil` | Prints up to 16 values, space-separated, to the console. Long lines wrap at 256 columns. |
| `clear()` | `nil` | Clears the console for every viewer. |
| `read()` | string | **Blocks** until a line is typed into the terminal, then returns it. |

```
print("what is your name?")
let name = read()
print("hello, " + name)
```

### Timing

| Function | Returns | Description |
| --- | --- | --- |
| `sleep(ticks)` | `nil` | **Blocks** for `ticks` game ticks (20 = 1 second). Non-positive returns instantly. |
| `gametime()` | number | Total ticks the world has existed. Monotonic. |
| `daytime()` | number | Overworld clock time; 0–23999 per day cycle. |
| `day()` | number | `floor(daytime() / 24000)` — the current day number. |
| `uptime()` | number | Ticks since this program started (0 when not running). |

```
if daytime() > 13000 and daytime() < 23000 { print("night time") }
```

### Wifi networking

Pots reach each other by hostname across any distance and across dimensions, as long as both
chunks are **loaded**. Names are lowercase, 1–16 characters of `a-z 0-9 - _`.

| Function | Returns | Description |
| --- | --- | --- |
| `hostname()` | string | This pot's name. |
| `sethost(name)` | bool | Renames the pot. `false` if invalid or already taken. |
| `send(host, value)` | bool | Sends to one host. `false` if the host is unknown/unloaded or its mailbox is full. |
| `broadcast(value)` | number | Sends to every other host; returns the delivery count. |
| `peers()` | list | Sorted hostnames of all reachable pots, **including this one**. |
| `has_msg()` | bool | Whether a message is waiting. |
| `recv()` | list | **Blocks** forever until a message arrives. |
| `recv(timeout)` | list or `nil` | **Blocks** up to `timeout` ticks; `nil` on timeout. |

A received message is always a two-element list: `[sender_hostname, payload]`.

Payloads may be `nil`, bools, numbers, strings and lists (nested up to 8 deep). Sending a
function raises `send: cannot send a function`. Payloads are deep-copied on delivery.

```
# door controller
sethost("door")
while true {
    let msg = recv()
    let from = msg[0]
    let body = msg[1]
    if body == "open" {
        rs_set("north", 15)
        send(from, "ok")
    } else if body == "close" {
        rs_set("north", 0)
        send(from, "ok")
    }
}
```

### Redstone

Sides are `up`, `down`, `north`, `south`, `east`, `west`. An unknown side is a runtime error.

| Function | Returns | Description |
| --- | --- | --- |
| `rs_set(side, level)` | `nil` | Emit `level` (clamped 0–15) out of that side. Both weak and strong power. |
| `rs_get(side)` | number | The redstone signal the neighbour on that side is feeding into the pot. |
| `rs_reset()` | `nil` | Set all six outputs to 0. |

Output levels persist when the program stops and when the chunk unloads. `reboot` clears them.

```
# repeater with a delay: mirror the east input onto the west output, 1s later
while true {
    let level = rs_get("east")
    sleep(20)
    rs_set("west", level)
}
```

### World sensors

| Function | Returns | Description |
| --- | --- | --- |
| `pos()` | list | `[x, y, z]` of the pot. |
| `dim()` | string | Dimension id, e.g. `"minecraft:overworld"`. |
| `biome()` | string | Biome id, e.g. `"minecraft:plains"`. |
| `weather()` | string | `"clear"`, `"rain"` or `"thunder"`. |
| `light()` | number | Light level (0–15) of the block directly above the pot. |

### Players and sound

| Function | Returns | Description |
| --- | --- | --- |
| `players([range])` | list | Names of players within `range` blocks (default 16, clamped 0–64). |
| `say(text, [range])` | number | Sends `<hostname> text` to nearby players' chat; returns how many were reached. Text is truncated at 256 chars. |
| `beep([pitch])` | `nil` | Plays a note block "bit" sound. `pitch` is a semitone 0–24, default 12 (F#4, the natural pitch). |

```
# greeter
let seen = []
while true {
    let here = players(8)
    let i = 0
    while i < len(here) {
        if find(seen, here[i]) < 0 {
            say("welcome, " + here[i])
            beep(18)
            push(seen, here[i])
        }
        i = i + 1
    }
    sleep(40)
}
```

### Persistent disk

A string-to-string key/value store saved with the block. Values are stringified on write, so
what you read back is always a string — use `num()` to convert numbers back.

| Function | Returns | Description |
| --- | --- | --- |
| `store(key, value)` | `nil` | Writes. Errors if the key is over 64 chars, the value over 4,096, or the disk is at 256 keys. |
| `load(key)` | string or `nil` | Reads; `nil` if the key is absent. |
| `delkey(key)` | bool | Deletes; `false` if the key was absent. |
| `keys()` | list | All keys, in insertion order. |

```
let runs = num(load("runs") or "0")
runs = runs + 1
store("runs", runs)
print("run number " + str(runs))
```

Note that a list written with `store` comes back as its printed form (`"[1, 2, 3]"`), not as a
list. To round-trip a list, use `join`/`split`:

```
store("items", join(["axe", "rope"], ","))
let items = split(load("items") or "", ",")
```

### Math

| Function | Returns | Description |
| --- | --- | --- |
| `random()` | number | Uniform in `[0, 1)`. |
| `randint(a, b)` | number | Uniform integer, **inclusive** of both ends. Errors if `b < a`. |
| `floor(x)` `ceil(x)` `round(x)` | number | Rounding. `round` returns the nearest integer (halves go up). |
| `abs(x)` `sqrt(x)` | number | Absolute value, square root. |
| `pow(a, b)` | number | `a` to the power `b`. |
| `min(a, b)` `max(a, b)` | number | Exactly two arguments. |

### Values and conversion

| Function | Returns | Description |
| --- | --- | --- |
| `str(x)` | string | The printed form of any value. |
| `num(x)` | number or `nil` | Parses a string (whitespace trimmed); passes numbers through; returns `nil` if unparseable. Errors on bools, lists and nil. |
| `type(x)` | string | `"nil"`, `"bool"`, `"number"`, `"string"`, `"list"` or `"function"`. |
| `len(x)` | number | Length of a string or list. Errors on anything else. |

### Strings

| Function | Returns | Description |
| --- | --- | --- |
| `upper(s)` `lower(s)` | string | Case conversion. |
| `trim(s)` | string | Strips leading/trailing whitespace. |
| `split(s, sep)` | list | Splits on a literal separator (not a regex). An empty separator splits into single characters. |
| `join(list, sep)` | string | Joins stringified elements. |
| `sub(s, from, [to])` | string | Substring; both bounds are clamped into range, so it never errors. `to` defaults to the end. |
| `find(s, needle)` | number | Index of the first occurrence, or `-1`. |
| `chr(n)` | string | The character with code `n`. |
| `ord(s)` | number | Code of the first character. Errors on an empty string. |

### Lists

| Function | Returns | Description |
| --- | --- | --- |
| `push(list, value)` | list | Appends and returns the same list (so calls chain). |
| `pop(list)` | value | Removes and returns the last element. Errors when empty. |
| `remove(list, i)` | value | Removes and returns index `i`; negative indices count from the end. |
| `find(list, value)` | number | Index of the first equal element, or `-1`. |
| `range([from,] to)` | list | `[from, from+1, ..., to-1]`; `from` defaults to 0. The end is exclusive. |
| `len(list)` | number | Element count. |

`find` dispatches on its first argument: given a list it searches by equality, given a string
it searches for a substring.

---

## 11. Errors

Two kinds, both printed into the console in red.

**Compile errors** happen before anything runs and abort the whole run:

```
[compile error] line 3: expected '{' after if condition
```

**Runtime errors** stop the program where it stood:

```
[error] line 12: index 5 out of range (length 3)
```

There is no `try`/`catch` and no way to trap an error. Defensive code checks first:

```
if len(parts) > 1 { print(parts[1]) }
let value = load("k")
if value == nil { print("not set") }
```

Common runtime errors: `undefined variable 'x'`, `expected a number, got string`,
`division by zero`, `index N out of range`, `<fn> expects N argument(s), got M`,
`nil is not callable`, `call stack overflow`.

---

## 12. Complete examples

### Ore-sorting timer with a heartbeat over wifi

```
sethost("timer")

fn hms(ticks) {
    let secs = floor(ticks / 20)
    return str(floor(secs / 60)) + "m" + str(secs % 60) + "s"
}

let pulses = num(load("pulses") or "0")
while true {
    rs_set("up", 15)
    sleep(4)
    rs_set("up", 0)
    pulses = pulses + 1
    store("pulses", pulses)
    broadcast(["pulse", pulses, hms(uptime())])
    sleep(196)      # 10s total cycle
}
```

### Monitor that collects heartbeats

```
sethost("monitor")
let counts = []
let names = []

while true {
    let msg = recv(600)             # 30s timeout
    if msg == nil {
        print("[quiet] " + str(len(names)) + " known hosts")
        continue
    }
    let from = msg[0]
    let at = find(names, from)
    if at < 0 {
        push(names, from)
        push(counts, 1)
        print("new host: " + from)
    } else {
        counts[at] = counts[at] + 1
    }
    print(from + " -> " + str(msg[1]))
}
```

### Interactive console app

```
print("pot shell. commands: light, pos, who, set <key> <value>, get <key>, quit")
while true {
    print("? ")
    let line = trim(read())
    let parts = split(line, " ")
    let cmd = lower(parts[0])

    if cmd == "quit" {
        print("bye")
        break
    } else if cmd == "light" {
        print("light: " + str(light()) + "  weather: " + weather())
    } else if cmd == "pos" {
        print(join(pos(), ", "))
    } else if cmd == "who" {
        let here = players(32)
        if len(here) == 0 { print("nobody nearby") } else { print(join(here, ", ")) }
    } else if cmd == "set" and len(parts) > 2 {
        remove(parts, 0)                 # drop "set"
        let key = remove(parts, 0)       # take the key, the rest is the value
        store(key, join(parts, " "))
        print("stored " + key)
    } else if cmd == "get" and len(parts) > 1 {
        print(load(parts[1]) or "(unset)")
    } else {
        print("unknown command")
    }
}
```

### Redstone-triggered alarm

```
sethost("alarm")
let armed = true
while true {
    if armed and rs_get("down") > 0 {
        say("intruder detected!", 32)
        let i = 0
        while i < 6 {
            beep(24)
            rs_set("up", 15)
            sleep(5)
            rs_set("up", 0)
            beep(0)
            sleep(5)
            i = i + 1
        }
        broadcast(["alarm", pos()])
        sleep(100)
    }
    sleep(2)
}
```

---

## 13. Gotchas

* **`0` and `""` are truthy.** Use `x == 0` or `len(s) == 0` explicitly.
* **Assignment needs a prior `let`.** `x = 1` on a fresh name is a runtime error, not an
  implicit declaration.
* **No closures, no nested functions.** Share state through globals.
* **`<` and friends are numbers only** — you cannot sort strings with them.
* **Out-of-range indexing errors** rather than returning `nil`; check `len` first.
* **Lists are shared references**, so `push(f(l), x)` may mutate the caller's list. Copy with
  `l + []` when you need a snapshot.
* **`store` stringifies.** Numbers come back as strings; wrap reads in `num()`.
* **A reloaded chunk restarts the program from line 1.** Persist anything important to disk.
* **`peers()` includes this pot**, so filter yourself out when iterating; `broadcast` already
  skips you.
* **Typing `stop` while a program is running halts it** instead of feeding `read()`.
* **`sleep` counts game ticks, not seconds** — multiply seconds by 20.
* **Unloaded pots vanish from the network**, and `send` to them returns `false`. Check the
  return value if delivery matters.
