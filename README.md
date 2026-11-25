# Ao Oni J2ME

A from-scratch native Java ME (CLDC 1.1 / MIDP 2.0) port of *Ao Oni*, plus a RPG Maker XP data compiler to go with it

I didn't try to run Ruby/RGSS on a feature phone (that would be insane). Instead, the build tools take the original game's `.rxdata` maps, events and graphics and compile them into a couple of small big-endian binary files (`rooms.pak` and `meta.bin`) plus pruned 16x16 indexed PNGs. A tiny Java ME engine then just reads those and runs the game.

## Requirements

### To build it

- **JDK 8** (newer ones break the WTK preverifier, trust me)
- **Sun Java Wireless Toolkit (WTK) 2.5.2**
- **ProGuard** 5.x or 6.x, dropped at `tools\proguard.jar`. Technically optional, but I strongly recommend it. WTK 2.5.2's `preverify.exe` rejects JDK 8 bytecode pretty often, and ProGuard's `-microedition` flag shrinks the JAR *and* preverifies it for CLDC 1.1 in one go.
- **Python 3.x**
 `pip install rubymarshal pillow` if you can't be bothered
- The original *Ao Oni* **`Data/`** and **`Graphics/`** folders
- The **RPG Maker XP RTP** `Graphics/` folder, but only if the game pulls base tilesets/autotiles from it.

> **Heads up:** none of the original game assets (`Data/`, `Graphics/`, `Audio/`) or RTP files are in this repo. You have to supply your own.

### To run it

- Any CLDC 1.1 / MIDP 2.0 phone with a 240x320 screen, ideally the Nokia 6300 or similar S40 3rd Ed. FP2 device
- Max JAR size: 1 MB (we're at roughly 710 KB right now)
- Java heap: 2 MB
- Or an emulator: **KEmulator (nnmod)** or **FreeJ2ME**, locked to `240x320` with a `2 MB` heap cap. WTK works too but it's way more forgiving than real hardware.

## Target Hardware / How it fits

| Constraint | Nokia 6300 limit | What the engine does |
|---|---|---|
| Platform | CLDC 1.1 / MIDP 2.0 | Pure Java 1.3 bytecode, only 6 core classes |
| Screen | 240x320 | Full-screen `GameCanvas`, 16x16 tiles (50% of the RMXP size), 20 FPS |
| Max JAR size | 1 MB | One indexed `rooms.pak` (~57 KB deflated) + quantized indexed PNGs, ~710 KB total |
| Java heap | 2 MB | One room and one tileset loaded at a time (128 KB peak for tilesets), a capped 16-sheet sprite cache, and no allocations in the main loops |


## Controls

I removed all the on-screen control hints to keep the UI clean, so here's the full list.

### Exploration
| Key | Action |
|---|---|
| **D-Pad** | Move Hiroshi |
| **5 / OK / Left Softkey** | Interact / Inspect |
| **7** | Open inventory (`Items`, `SAVE`, `TITLE`) |

### Title screen & Game Over
| Key | Action |
|---|---|
| **D-Pad Up / Down** | Pick an option (`2` / `8` also work on Game Over) |
| **5 / OK / Left Softkey** | Confirm (`NEW GAME`, `CONTINUE`, `SHUTDOWN`, `RETRY`, `TITLE`) |
| **Right Softkey** | Exit (Title) / back to Title (Game Over) |

### Dialogue & choices
| Key | Action |
|---|---|
| **5 / OK / Left Softkey** | Next text / confirm choice |
| **D-Pad Up / Down** | Move the choice cursor |
| **Right Softkey / `*`** | Cancel choice (when the game allows it) |

### Inventory (`7`)
| Key | Action |
|---|---|
| **D-Pad Up / Down** | Scroll through items, `SAVE`, `TITLE` |
| **5 / OK / Left Softkey** | Select an item (opens the `USE` / `MERGE` prompt) or trigger `SAVE` / `TITLE` |
| **D-Pad Left / Right** | Switch between `USE` and `MERGE` |
| **5 / OK / Left Softkey** *(in Merge mode)* | Combine the highlighted second item with the first one |
| **`7` / Right Softkey / `*`** | Back / close inventory |

### Number input (safes & passcodes)
| Key | Action |
|---|---|
| **`0`-`9`** or **D-Pad Up / Down** | Set the digit |
| **D-Pad Left / Right** | Move the digit cursor |
| **5 / OK / Left Softkey** | Submit the code |

### Naming screen
| Key | Action |
|---|---|
| **D-Pad** | Move around the character grid and `DEL` / `CLR` / `OK` |
| **5 / OK / Left Softkey** | Insert a character or press the action button |
| **`*` / Right Softkey** | Quick backspace |
| **`#`** | Confirm the name right away |

*(In PC emulators like WTK or KEmulator you can also just type with your keyboard on the naming screen: letters, Space, Backspace and Enter all work.)*

## Project structure

```text
.
├── src/
│   ├── AoOniMIDlet.java     # MIDlet lifecycle entry point
│   ├── GameData.java        # meta.bin & rooms.pak loader, state, caches, RMS saves, item recipes
│   ├── RoomMap.java         # AOM2/AOC1 loader, page evaluator, collision & zero-alloc BFS
│   ├── MapEvent.java        # event state, move routes, oni variant types
│   ├── EventVM.java         # bytecode VM & RPN expression evaluator
│   └── GameScreen.java      # 20 FPS GameCanvas loop, input, AI, renderer, menus & naming UI
├── tools/
│   ├── compile_aooni.py     # compiles Data/*.rxdata -> res/rooms.pak (AOPK)
│   ├── bake_assets.py       # bakes 16x16 indexed PNGs -> res/gfx/ and res/meta.bin (AOG3)
│   └── proguard.jar         # ProGuard (NOT included, download it yourself)
├── res/
│   ├── Data/                # unpacked original Ao Oni Data/*.rxdata (supply your own)
│   ├── Graphics/            # unpacked original Ao Oni Graphics/ (supply your own)
│   ├── gfx/                 # generated 16x16 indexed PNGs
│   ├── meta.bin             # generated metadata & tile remap tables
│   └── rooms.pak            # generated archive of all maps + common events
├── MANIFEST.MF
├── requirements.txt
└── build.bat
```

## Building on Windows

1. Put the original unpacked `Data` and `Graphics` folders inside `res\`.
2. Compile all the maps and common events into `res\rooms.pak`:
   ```powershell
   python tools\compile_aooni.py "res\Data" "res\rooms.pak"
   ```
3. Bake the pruned 16x16 tilesets, sprites, portraits, Game Over screen and `res\meta.bin`:
   ```powershell
   python tools\bake_assets.py "res\Data" "res\Graphics" "res" --rtp "C:\Program Files (x86)\Common Files\Enterbrain\RGSS\Standard\Graphics"
   ```
   *(Add `--colors 32` if you need more JAR size headroom for audio later.)*
4. Open `build.bat` and double check that `JDK_HOME` and `WTK_HOME` point to where you actually installed things. This is the step most people mess up, I did too.
5. Compile, preverify and launch in the WTK emulator:
   ```powershell
   .\build.bat
   ```

The finished package ends up in `dist\AoOni.jar` and `dist\AoOni.jad`. For testing that's closer to a real Nokia 6300, run `dist\AoOni.jar` in KEmulator or FreeJ2ME before you copy it to a phone over USB, Bluetooth or microSD.

## Credits & disclaimer

*Ao Oni* was created by **noprops**. This repo only has the clean-room J2ME runtime and the asset conversion tools. No copyrighted game assets or RPG Maker XP RTP files are distributed here, so please don't open issues asking where to download them.
