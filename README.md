<p align="center"><img src="docs/icon.png" width="128" alt="icon"></p>
<h1 align="center">Litematica Printer Autyism Edition</h1>
<p align="center">A standalone Litematica printer for Minecraft 1.21.11 and 26.1–26.1.2 that builds schematics from the bottom up and takes care with redstone, rails and laggy servers.</p>
<p align="center">适用于 Minecraft 1.21.11 和 26.1–26.1.2 的独立投影打印机：从最底层开始往上打印，红石、铁轨和服务器延迟都处理得更稳。</p>

<p align="center"><a href="#english">English</a> · <a href="#简体中文">简体中文</a></p>

[![Minecraft 1.21.11 | 26.1–26.1.2](https://img.shields.io/badge/Minecraft-1.21.11_%7C_26.1--26.1.2-62B47A)](https://www.minecraft.net/) [![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4)](https://fabricmc.net/) [![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE.md)

# English

Litematica Printer Autyism Edition places the blocks of your loaded [Litematica](https://modrinth.com/mod/litematica) schematic around you automatically. It can also fill an area, remove water and lava, mine a selection and hand bedrock to a bedrock-breaking mod.

It is a standalone mod (not an add-on), based on the BiliXWhite and water2004 forks of Litematica Printer and reworked so that what it places matches the schematic, also on real servers with lag. It replaces other Litematica Printer builds; do not install both.

## Features

### New in this edition

Compared with the 1.21.11 printer builds it is based on, this edition adds or reworks:

- Layered printing from the bottom up, a work radius of up to 4096 and automatic reach in singleplayer.
- A pause while you use containers, so taking materials from a chest no longer causes wrong blocks.
- Fixed placement of doors (hinge side), vines, levers, buttons and double chests, signs that can be placed against other signs, and server-confirmed head turns for pistons, observers and other direction-sensitive blocks.
- Safe rail placement, and redstone adjustments (note blocks, repeaters and more) that wait for the server between clicks.
- Waiting for the server where it matters, so lag or Meteor's NoGhostBlocks cannot cause double placements; the lag pause now also works in singleplayer.
- Shulker box restocking through Advanced Shulkerboxes, and shulker boxes printed only with matching contents.
- Built-in tool switching with durability protection, and instant-first mining.
- Bedrock mode support for LXYan2333's Fabric-Bedrock-Miner, a backend choice and an editable block list.
- Optional bucket printing for water, lava and filled cauldrons.
- Import of your settings from a previous Litematica Printer install.

### Printing that matches the schematic

- **Correct orientation.** Stairs, trapdoors (top or bottom half and facing), doors (including the hinge side and double doors), levers, buttons, grindstones, vines, glow lichen, ladders, signs, banners and heads are placed the way the schematic shows them.
- **Doors wait for their neighbours.** A door is only placed once the blocks beside it that decide its hinge side are in place and visible, so the game cannot flip the hinge.
- **Head turns the server has really seen.** Blocks whose direction the server takes from your head (pistons, observers, dispensers, droppers, crafters, barrels, rails, levers, buttons, ladders) are only placed after the server has registered the turn. Your camera never moves.
- **Blocks that need support wait for it.** Torches, levers, buttons, vines and similar blocks wait until the block they hang on exists, instead of attaching to the wrong side. Sand, gravel and anvils wait for the right block below them.
- **Double chests and shulker boxes.** The second half of a double chest waits until the first half is there, so the two join. A shulker box is only placed if you carry one whose contents exactly match the schematic (an empty box for an empty one); in creative the right box is created for you.
- **Signs and clickable blocks.** When a sign is placed where the schematic has one, its front text is filled in from the schematic instead of opening the sign editor. When the printer places against chests, signs, buttons, note blocks and other clickable blocks, it sneaks so nothing gets opened or pressed.

### Redstone and rails

- **Safe observers.** With *Safe Observer Placement* on (default), an observer is only placed when it will not fire into your machine. Observers that cannot be placed safely are skipped on purpose, and pistons that an observer would fire wait until the block that observer watches is finished.
- **Safe rails.** Before each rail, the printer simulates vanilla's rail-connection rules. A rail is only placed when it will end up in the schematic's shape, will not bend rails that are already placed, and will not pop off for lack of support. Connected rails get a placement order planned in advance. A rail that vanilla cannot place correctly in any order is left empty; it is never placed wrong and broken again.
- **Adjustable blocks, one confirmed click at a time.** Note blocks are tuned, repeaters set to their delay, comparators to their mode, wooden doors, trapdoors and fence gates opened or closed and levers flipped. Each click waits until the server has confirmed the previous one, so lag cannot make it overshoot.
- **Stackable blocks.** Candles, sea pickles, snow layers, flower beds and double slabs are built up to the count in the schematic.
- **Bottom-up order.** With layered printing, machines are built from the ground up, so upper pistons and observers are not placed before the parts beneath them.

### Big builds

- **Layered printing, bottom-up** (on by default). Only the lowest unfinished layer within your range is printed; the next layer starts when it is done. A message reports every finished layer: either all blocks placed, or how many are correct, in the wrong state, the wrong block, or missing. If a layer cannot be finished (for example, materials ran out), the printer continues upward after a few seconds and comes back to it after reaching the top.
- **Large work radius.** The radius can be set up to 4096. Only the part of the schematic inside it is scanned, and completely empty sections are skipped, so the radius itself does not add work. The server's reach still limits what can be placed; in singleplayer the printer raises your reach up to the vanilla maximum of 64.
- **Automatic reach in singleplayer.** With cheats allowed, the printer raises your block interaction range to match the work radius (vanilla limit 64) with the `/attribute` command, and waits until it applies before starting. In singleplayer it never works beyond the range the game accepts.
- **Progress display.** Turn on *Show Work Status* for a progress bar under the crosshair: blocks placed within range, wrong states, wrong blocks and the current layer. The *Missing Material HUD* (on by default) lists materials the printer could not find.

### Materials and inventory

- **Pauses while you use containers** (on by default). From the moment you right-click a chest or another block with a screen, a villager, a horse or a storage minecart until shortly after the screen closes, and while any inventory screen is open, the printer pauses (all modes). Taking materials out of a chest no longer leads to wrong blocks.
- **Shulker box restocking** (on by default). When a material runs out, the printer finds a shulker box in your inventory that holds it, opens it in the background (no screen appears) and takes the stack out. This uses Advanced Shulkerboxes or QuickShulker; on servers with the AxShulkers plugin, set the source to *Plugin*. When your inventory is nearly full, it first puts away items you do not need right now, preferably into a box that already holds them.
- **Hotbar handling.** Materials are moved into the hotbar slots allowed by Litematica's *pickBlockableSlots* setting. When printing starts, your selected hotbar slot is re-synced with the server, so mods that filter "duplicate" slot packets cannot make it place the wrong item.

### Breaking, filling and bedrock

- **Built-in tool switching.** Before the printer breaks a block, it picks the fastest suitable tool from your whole inventory, preferring tools that make the block drop. Tweakeroo is not needed, and your own mining is not affected.
- **Tool durability protection.** If the tool it would use has 10 durability or less left, all breaking stops instead of falling back to a worse tool, a non-breakable item goes into your hand and a warning appears. Repair or replace the tool, then turn the printer off and on.
- **Instant-first mining.** Mining mode breaks everything it can break instantly first and leaves slow blocks for last; walking on brings new instant blocks to the front. By default this only happens when you have an Efficiency V tool and Haste II.
- **Filling.** Fills air, fluids and overwritable blocks inside your Litematica area selection with a block from a list (default: cobblestone) or the block in your hand. You can choose a facing, for example to place top slabs.
- **Fluid removal.** Places blocks (default: sand) into water and lava inside the area selection, including flowing fluid.
- **Mining.** Breaks every non-liquid block inside the area selection, with an optional whitelist or blacklist, or Tweakeroo's block-breaking restriction lists.
- **Bedrock breaking.** Hands every bedrock block in your area selection to a bedrock-breaking mod: Fabric-Bedrock-Miner (LXYan2333), Bedrock Miner (bunnyi116) or BlockMiner. *Auto* prefers LXYan2333's. Add other blocks such as end portal frames or reinforced deepslate to the block list; with Fabric-Bedrock-Miner or Bedrock Miner they are also added to that mod's own allow list automatically. By default it ignores Litematica's render layers and works on the whole selection.

### Fluids

- **Bucket printing (off by default).** Turn on *Print Fluids With Buckets* to place schematic water and lava sources with buckets and to fill cauldrons that should hold water, lava or powder snow. A source is only placed after the walls and floor around the whole body of fluid are printed, so nothing leaks. In survival, each source uses one filled bucket.
- **Break Ice for Water** (off by default). The classic survival method: place ice, break it, then place waterlogged blocks into the water.

### Laggy servers

- **Waits for the server where it matters.** The printer does not touch a spot again until the server has answered its last action there, so lag cannot make it place twice. This also makes it work with client mods that only show a placed block after the server confirms it, such as Meteor's NoGhostBlocks.
- **Careful item swaps.** In survival, taking items from the main inventory waits until earlier actions are confirmed, and a stack that is about to run out waits for the server's count.
- **Lag pause.** *Lag Detection* (on) pauses the printer when the server has sent nothing for about a second, and resumes when data arrives again. This also works in singleplayer.
- **Easy Place protocol when available.** In singleplayer, or on a server running Servux, new blocks are placed directly in the right state without any head turn. Elsewhere the printer sends a look direction instead. Carpet's older protocol is only used if you select it in Litematica.
- **Turns itself off** when you die or disconnect (*Auto Disable Printer*).

### Also included

- Auto-stripping logs, live coral as a stand-in for dead coral, composter filling, bone meal for crops, breaking wrong or extra blocks, skip and overwrite lists, block highlighting, packet-based placing and mining, and a choice of work-area shape and scan order.
- Block lists accept block IDs (`minecraft:stone`), names in your game language, tags (`#minecraft:logs`), partial matches (`glazed_terracotta,c`) and pinyin for Chinese names. The settings search also matches pinyin.
- Settings from a previous Litematica Printer install (BiliXWhite or water2004 builds) are imported on first start.
- With Autyism's Litematica Enhancement (ALE) installed, every block list gets a **Pick blocks...** button with a visual block picker.

## Screenshots

![A furnished villa with a pool, printed with layered printing](docs/images/printed-villa.png)

A furnished villa with a pool, printed with layered printing.

![A medieval-style tavern printed by the printer](docs/images/printed-tavern.png)

A medieval-style tavern with interior, printed by the printer.

![A timber-framed manor printed by the printer](docs/images/printed-manor.png)

A timber-framed manor, printed by the printer.

![Printing in progress: the walls are placed, the roof is still the schematic preview](docs/images/printing-in-progress.png)

Printing in survival: the walls are done, the roof is still the schematic's preview.

![The finished cottage](docs/images/printed-cottage.png)

The same cottage when the printer is done. Every block matches the schematic, including stair directions, the door and the lanterns.

![The printer settings, Filling tab](docs/images/settings-filling.png)

The settings screen. Every mode has its own tab; this is the Filling tab.

![The Bedrock tab](docs/images/settings-bedrock.png)

Bedrock tab: choose the bedrock-breaking mod and the blocks to break.

![Block picker for the bedrock block list](docs/images/bedrock-block-picker.png)

With ALE installed, block lists get a visual picker. Here end portal frames are added to the bedrock list.

## How to use

### Default keys

| Action | Default key | Where to change it |
|---|---|---|
| Turn the printer on or off (*Printer On/Off*) | Caps Lock | Printer settings, Core tab |
| Open the printer settings | Z + Y (hold Z, press Y) | Printer settings, Hotkeys tab |
| Cycle mode (Printing → Mining → Filling → Fluid Removal → Bedrock, one at a time) | not set | Printer settings, Hotkeys tab |
| Close all modes (turns the printer and every mode off) | Left Ctrl + G | Printer settings, Hotkeys tab |
| Start or stop one mode, toggle Layered Printing or Break Ice for Water | not set | Hotkey button next to that option in its tab |

**Printer On/Off** turns the printer on and off. While it is on, every mode whose *Enable* option is on works; if no mode is enabled, turning it on enables Printing. A mode's own hotkey starts that mode alone while the printer is off, and adds or removes it while the printer runs. **Cycle Mode** switches to the next mode and, with *Cycle Mode Turns Printer Off* (on by default), also turns the printer off, so the new mode never starts working by itself. A message above the hotbar shows the result of every key.

### Opening the settings

- Press **Z + Y** while in a world.
- Open Litematica's main menu (**M** by default) and click **Printer Settings**.
- In Mod Menu, select Litematica Printer Autyism Edition and click the settings button.
- Use the mod drop-down at the top right of any MaLiLib settings screen.

### Commands

The mod adds no commands. In singleplayer with cheats allowed, *Auto Raise Reach* runs the vanilla `/attribute` command for you when needed.

### Print a schematic

1. Load and place a schematic with Litematica as usual.
2. Carry the materials, or shulker boxes that contain them. In creative mode the printer takes them from the creative inventory.
3. Stand near the build and press **Caps Lock**. With no other mode enabled, the printer starts in Printing mode and places blocks within reach, lowest layer first. It never moves you; walk along the build or use a larger work radius.
4. Press **Caps Lock** again to stop. For a progress bar, turn on **Show Work Status** in the Core tab.

The printer follows Litematica's render layers, so you can limit printing to certain layers with Litematica's layer controls.

### Print a big build from one spot

1. In singleplayer with cheats allowed, set **Work Radius** (Core tab) to, for example, 32 or 64. The printer raises your reach automatically, up to 64.
2. Keep **Layered Printing** on, stand near the middle of the build and press Caps Lock. It builds layer by layer from the bottom and reports each finished layer.
3. On a server, the radius is limited by the server's reach. Leave Work Radius at `0` (your normal reach) unless the server allows more.

### Fill, remove fluids or mine an area

1. Select the area with Litematica's area selection tool.
2. Open the matching tab (**Filling**, **Fluid Removal** or **Mining**) and check its block lists.
3. Start the mode: press its own hotkey (set it next to its **Enable** option), or select it with **Cycle Mode** and press Caps Lock.

### Break bedrock

1. Install a supported bedrock-breaking mod and play in survival (bedrock mode does not work in creative).
2. Select the area with Litematica's area selection tool.
3. In the **Bedrock** tab, keep **Bedrock Miner Backend** on *Auto* or choose a mod.
4. Start bedrock mode with the hotkey of **Enable Bedrock Breaking**, or select it with **Cycle Mode** and press Caps Lock. The printer turns the bedrock miner on while bedrock mode runs and restores it afterwards.

## Settings

Names are shown as they appear in game. The settings screen has an **All** tab and one tab per topic.

### Core

| Option | Default | What it does |
|---|---|---|
| Printer On/Off | off (Caps Lock) | Turns the printer on or off; see *Default keys*. |
| Work Radius | 0 | How far from you the printer works, up to 4096. `0` uses your normal reach. |
| Auto Raise Reach (Singleplayer) | on | In singleplayer the printer never works beyond your reach; with cheats allowed, this raises the reach to the work radius (max 64). |
| Pause While Using Containers | on | Pauses while you open or use a container or inventory screen. |
| Show Work Status | off | Progress display under the crosshair. |
| Missing Material HUD | on | Lists missing materials in Litematica's info HUD area. |
| Lag Detection / Lag Detection Max | on / 20 ticks | Pauses when the server has sent nothing for this long. |
| Work Area Shape | Sphere | Shape of the work area: Sphere, Cube or Octahedron. |
| Iteration Order | X→Z→Y | Order in which positions are checked; each axis can be reversed. |
| Classify by Block Type | off | Handles one block type per pass to reduce item switching. |
| Iteration Time Limit | 8 ms | Maximum time per tick spent checking positions. |
| Auto Disable Printer | on | Turns the printer off when you die or disconnect. |

### Hotkeys

| Option | Default | What it does |
|---|---|---|
| Open Settings Menu | Z + Y | Opens this settings screen. |
| Cycle Mode | not set | Switches to the next mode and turns the others off. Bedrock is skipped when no bedrock-breaking mod is installed. |
| Cycle Mode Turns Printer Off | on | Cycle Mode also turns the printer off, so a key pressed by accident never starts a mode. Off: the printer stays as it is. |
| Close All Modes | Left Ctrl + G | Turns the printer and every mode off. |

### Printing

| Option | Default | What it does |
|---|---|---|
| Enable Printing | off | Printing mode; it works while the printer is on. Turning the printer on with no mode enabled enables it. |
| Layered Printing (bottom-up) | on | Prints the lowest unfinished layer first. |
| Selection Type | Visible Area | *Below Player* or *Above Player* limit printing to blocks below or above your feet; Litematica's render layers always apply. |
| Use Easy Place Protocol | on | Uses Litematica's Easy Place protocol when the server supports it (singleplayer, Servux). |
| Safe Observer Placement | on | Skips observers that would fire into your machine. |
| Safe Rail Placement | on | Only places rails that end up in the right shape. |
| Note Block Auto Tuning | on | Tunes note blocks to the schematic's pitch. |
| Print Fluids With Buckets | off | Places water and lava sources and fills cauldrons with buckets. |
| Break Ice for Water | off | Makes water by placing and breaking ice (survival only). |
| Place in Air | on | Places blocks with nothing to click against; blocks that need support still wait. |
| Overwrite Printing - Toggle / List | on / snow, water, lava, bubble column, short grass | Blocks on the list are replaced directly. |
| Skip Placement - Toggle / List | off / empty | Never places blocks on the list. |
| Skip Waterlogged Blocks | off | Skips blocks that are waterlogged in the schematic. |
| Break Wrong Blocks / Break Extra Blocks | off | Breaks blocks that do not match the schematic, or are not in it. |
| Break Wrong State Blocks (Experimental) | off | Breaks matching blocks with a wrong state that a click cannot fix. |
| Always Sneak | off | Sneaks for every placement. |
| Use Quick Shulker Boxes | on | Restocks missing materials from shulker boxes in your inventory. |
| Shulker Source | Mod (Quick Shulker) | *Mod*: Advanced Shulkerboxes or QuickShulker, whichever is installed. *Plugin*: servers with AxShulkers. |
| Return to Shulker When Full | on | Puts items back into shulker boxes when the inventory is full. |

The Printing tab ends with the placing settings, which Filling and Fluid Removal use too:

| Option | Default | What it does |
|---|---|---|
| Placement Using Packets | off | Places without client-side prediction; can help on strict servers. |
| Placement Interval Time | 1 tick | Ticks between placement rounds. Not recommended at 0 for redstone. |
| Blocks to Place Per Tick | 1 | Placements per round; `0` means no limit. |
| Placement Cooldown Time | 3 ticks | Wait before retrying the same spot. Do not set it to 0. |
| Falling Block Check | on | Sand, gravel, anvils and the like wait for the right block below. |

### Breaking Blocks

| Option | Default | What it does |
|---|---|---|
| Auto Tool Switch | on | Picks the best tool from your inventory before breaking. |
| Tool Durability Protection / Durability Threshold | on / 10 | Stops breaking instead of using a tool at or below this durability. |
| Check Block Hardness | on | Skips unbreakable blocks. |
| Mining Progress Threshold | 100 % | Counts a block as broken at this progress (70–100 %). The vanilla server accepts 70 %, but anti-cheat may not. |
| Instant Mining | off | Breaks a block in one tick when one tick of progress reaches the threshold above; only matters below 100 %. |
| Breaking Interval Time / Blocks to Break Per Tick / Mining Cooldown Time | 1 tick / 1 / 3 ticks | Breaking speed limits. |
| Breaking Restriction Source / Mode / Whitelist / Blacklist | Custom / No Limit | Which blocks the printer may break at all (Mining mode, and wrong or extra blocks while printing), or Tweakeroo's lists. |

### Mining, Filling, Fluid Removal

| Option | Default | What it does |
|---|---|---|
| Enable Mining | off | Breaks blocks in the area selection. |
| Instant-First Only With Eff V + Haste II | on | Instant-first mining only with Efficiency V and Haste II; off = always. |
| Mining Restriction Source / Mode / Whitelist / Blacklist | Custom / No Limit | Extra limits for Mining mode only; the Breaking Restriction applies as well. |
| Enable Filling | off | Fills the area selection. |
| Fill Block Mode / Block List | Block List / cobblestone | Fill with blocks from the list, or with the block in your hand (*Held Item*). |
| Fill Block Facing | None | Facing for filled blocks, for example top or bottom slabs. |
| Enable Fluid Removal | off | Replaces fluids in the area selection. |
| Include Flowing Liquids | on | Also fills flowing water and lava. |
| Fluid Filling Block List / Fluid List | sand / water, lava | Blocks used, and fluids removed. |

### Bedrock

| Option | Default | What it does |
|---|---|---|
| Enable Bedrock Breaking | off | Sends blocks in the area selection to the bedrock-breaking mod. |
| Bedrock Miner Backend | Auto | Which mod to use. Auto: Fabric-Bedrock-Miner (LXYan2333), then Bedrock Miner (bunnyi116), then BlockMiner. |
| Ignore Render Layers | on | Works on the whole selection regardless of Litematica's render layers. |
| Bedrock Mode Block List | `minecraft:bedrock` | Blocks to break; also added to the allow list of Fabric-Bedrock-Miner or Bedrock Miner. |

### Highlighting

| Option | Default | What it does |
|---|---|---|
| Enable Block Highlighting | off | Outlines blocks the printer places (white), adjusts (green), breaks (red) or fails to place (gray). |
| Highlight Style / Fade-out Duration / See-Through Mode | Outline / 0.5 s / off | Look of the highlights; colors are adjustable. |

## Requirements

There is a separate jar for each Minecraft version:

| Minecraft | Jar | Java | Fabric Loader | MaLiLib | Litematica |
|---|---|---|---|---|---|
| 1.21.11 | `litematica-printer-autyism-1.0.0.jar` | 21 | 0.17.0 or newer | 0.27.0 or newer | 0.26.0 or newer |
| 26.1, 26.1.1, 26.1.2 | `litematica-printer-autyism-1.0.0+26.1.2.jar` | 25 | 0.19.3 or newer | 0.28.12 or newer | 0.27.14 or newer |

[Fabric API](https://modrinth.com/mod/fabric-api), [MaLiLib](https://modrinth.com/mod/malilib) and [Litematica](https://modrinth.com/mod/litematica) are required, each in the build for your Minecraft version.

Optional:

- [Mod Menu](https://modrinth.com/mod/modmenu): settings button in the mod list.
- Autyism's Litematica Enhancement (ALE): visual block picker for all block lists.
- [Tweakeroo](https://modrinth.com/mod/tweakeroo): use its block-breaking restriction lists for the printer.
- [Advanced Shulkerboxes](https://modrinth.com/mod/advanced-shulkerboxes) or QuickShulker (mod id `quickshulker`): shulker box restocking. Install it on your client and on the server.
- AxShulkers (server plugin): shulker box restocking on servers that use it.
- [Servux](https://modrinth.com/mod/servux) on the server: Easy Place protocol in multiplayer.
- A bedrock-breaking mod for bedrock mode: [Fabric-Bedrock-Miner](https://modrinth.com/mod/fabric-bedrock-miner) (LXYan2333), [Bedrock Miner](https://modrinth.com/mod/next-fabric-bedrock-miner) (bunnyi116) or [BlockMiner](https://github.com/z7087/blockminer).

**Side:** client only. The server does not need this mod; only the optional integrations above have server parts.

## Compatibility

- **Other printers:** do not install together with another Litematica Printer build (BiliXWhite, water2004, aleksilassila). The game refuses to start next to them (mod ids `litematica-printer` and `litematica_printer`).
- **Sodium and Iris:** no known problems; the printer was tested in a 1.21.11 modpack that uses both.
- **Tweakeroo:** works alongside it. The printer's tool switching is its own and does not need Tweakeroo's.
- **Meteor Client:** printing works with NoGhostBlocks on. Bedrock Miner (bunnyi116) cannot break anything while NoGhostBlocks is on; the printer shows a warning. Turn NoGhostBlocks off or use Fabric-Bedrock-Miner (LXYan2333).
- **Carpet:** Carpet's Easy Place protocol (V2) is only used if you select Version 2 in Litematica's settings, because it needs the server's `accurateBlockPlacement` rule.
- **ALE:** optional; neither mod needs the other.

## Installation

1. Install Fabric Loader for your Minecraft version (see the table above for the versions and Java you need).
2. Download Fabric API, MaLiLib and Litematica for that Minecraft version, and the jar of this mod for it.
3. Put all the jar files into your `mods` folder.
4. Remove any other Litematica Printer jar from the folder.
5. Start the game, join a world and press **Z + Y** to open the settings.

## FAQ

**I pressed Caps Lock and nothing happens.**

Look at the message above the hotbar: it says whether the printer is on and which modes are working. If another mode such as Mining is on instead of Printing, press Cycle Mode or the Printing hotkey. Check that a schematic is placed within your work radius, and that Litematica's render layers show the part you are standing at. The printer also pauses while a container or inventory screen is open and while the server is lagging. If materials are missing, the Missing Material HUD lists them.

**Some observers or rails stay empty. Is that a bug?**

No. Observers that would fire into your machine and rails that cannot be placed in the right shape are left empty on purpose. Place them yourself when the machine is ready. You can turn *Safe Observer Placement* off, but then observers may trigger your machine while it is being built.

**Can I build a large structure without walking around?**

In singleplayer with cheats allowed, yes: set a large Work Radius and keep Layered Printing on. On servers, you are limited to the server's reach.

**My reach stays high after printing in singleplayer.**

*Auto Raise Reach* changes your player's block interaction range in that world with the vanilla `/attribute` command, and the value stays. To reset it, run `/attribute @s minecraft:block_interaction_range base set 4.5`. Turn the option off if you do not want this.

**Can I use it on servers?**

It is client-side, so the server does not need it, but many servers do not allow printers; check the rules first. The printer sends its own look direction and places blocks quickly, which anti-cheat may notice. If placements get rejected, raise *Placement Interval Time*, keep *Blocks to Place Per Tick* at 1 and leave *Work Radius* at 0. With Servux on the server, most head turns are not needed.

**Why does it only use some hotbar slots?**

It uses the slots listed in Litematica's *pickBlockableSlots* setting (slots 1 to 5 by default). If that list is empty, it cannot take items from your main inventory.

**Water is not printed.**

*Print Fluids With Buckets* is off by default. Turn it on and carry filled buckets.

**Bedrock mode does nothing.**

Bedrock mode works in survival only, needs a supported bedrock-breaking mod and uses Litematica's area selection, not the schematic. See also the Meteor note under Compatibility.

Found a bug? Please open an issue: https://github.com/Autyism/litematica-printer-autyism/issues

## Known limitations

- **Left empty on purpose:** observers that would trigger your machine (with Safe Observer Placement on), rails that vanilla cannot place in the right shape (for example tightly stacked ascending powered rails), and anything that depends on those blocks.
- **Not printed:** entities (item frames, armor stands, paintings, minecarts), lily pads, bubble columns, piston heads, and flowing water or lava (these come from the sources). Waterlogged blocks are placed dry unless *Break Ice for Water* is on (survival only).
- **States set by the game:** redstone power, lit states, leaf distance, sapling growth, daylight detector power and similar values are not reproduced and may show as "wrong state" in the progress display.
- **Fluids with buckets:** the printer has to see a face of a neighbouring block, so some sources wait until you move. A source that gets covered by a layer above before it was placed must be placed by hand.
- **Multiplayer:** the work radius is limited by the server's reach; Auto Raise Reach only works in singleplayer; shulker restocking needs the matching mod or plugin on the server; the Easy Place protocol needs Servux on the server.
- **Many materials in survival:** when you use more block types than fit in the hotbar slots, printing is slower on laggy servers, because each swap from the main inventory waits for the server.
- **Bedrock Miner (bunnyi116) and Meteor's NoGhostBlocks** do not work together.
- **Translations:** English and Simplified Chinese are complete. In Traditional Chinese, Classical Chinese and Russian, newer options show Simplified Chinese or English text.

## Credits

This mod continues the work of:

- [aleksilassila](https://github.com/aleksilassila/litematica-printer): the original Litematica Printer
- [zhaixianyu](https://github.com/zhaixianyu/litematica-printer): fork of the original
- [BiliXWhite](https://github.com/BiliXWhite/litematica-printer) (BlinkWhite): the fork this edition is based on
- [water2004](https://github.com/water2004/litematica-printer): fixes merged into this edition
- [bunnyi116](https://github.com/bunnyi116): upstream contributor and author of Bedrock Miner
- and everyone credited in those projects, including Rofumer, Cjsah, EnderPhantomWing and MoRanpcy

Thanks also to masa (Litematica, MaLiLib, Tweakeroo), LXYan2333 (Fabric-Bedrock-Miner), z7087 (BlockMiner), Max Henkel (Advanced Shulkerboxes), kyrptonaught and MoRanpcy (QuickShulker), and pinyin4j (bundled, for pinyin search).

## License

GNU Affero General Public License v3.0 ([AGPL-3.0](LICENSE.md)), inherited from the upstream Litematica Printer projects: you may use, share and change the mod, as long as the source of your version stays available under the same license.

# 简体中文

Litematica Printer Autyism Edition（游戏内中文名“投影打印机 Autyism 版”）会自动把 [Litematica](https://modrinth.com/mod/litematica) 投影里的方块放到你身边。除了打印，它还能填充区域、排掉水和岩浆、挖空选区，以及把基岩交给破基岩模组去破。

它是独立模组（不是插件），基于 BiliXWhite 和 water2004 两个 Litematica Printer 分支，重点改进了“放出来的东西和投影一致”，在有延迟的真实服务器上也一样。它会替代其他投影打印机，不要和它们一起装。

## 功能

### 本版本的新内容

和它所基于的 1.21.11 版打印机相比，本版本新增或重做了：

- 从下往上的分层打印、最大 4096 的工作半径，以及单人世界自动调高触及距离。
- 使用容器时暂停，去箱子里拿材料不会再导致放错方块。
- 修正门（门轴）、藤蔓、拉杆、按钮和大箱子的放置，告示牌可以贴着告示牌放；活塞、侦测器等对朝向敏感的方块会等服务器确认转头后再放。
- 铁轨安全放置；音符盒、中继器等红石元件的调整每点一下都等服务器确认。
- 关键操作等服务器确认，延迟或 Meteor 的 NoGhostBlocks 都不会导致同一格放两次；延迟检测在单人世界里也能正常工作。
- 通过 Advanced Shulkerboxes 从潜影盒补货；投影里的潜影盒只用内容物一致的来放。
- 内置自动换工具和工具耐久保护，挖掘支持秒破优先。
- 破基岩模式支持 LXYan2333 的 Fabric-Bedrock-Miner，可选择使用哪个破基岩模组，方块列表可编辑。
- 可选用桶打印水、岩浆和装满的炼药锅。
- 自动导入旧版投影打印机的设置。

### 打出来和投影一致

- **朝向正确。** 楼梯、活板门（上半 / 下半和朝向）、门（包括门轴左右和双开门）、拉杆、按钮、砂轮、藤蔓、发光地衣、梯子、告示牌、旗帜和头颅都按投影里的样子放。
- **门会等旁边的方块。** 决定门轴的左右邻居方块放好、并且客户端能看到之后才放门，游戏不会把门轴改到另一边。
- **等服务器真正转过头再放。** 服务器按“头部朝向”决定方向的方块（活塞、侦测器、发射器、投掷器、合成器、木桶、铁轨、拉杆、按钮、梯子），要等服务器确认转头之后才放。你的视角不会被转动。
- **需要支撑的方块先等支撑。** 火把、拉杆、按钮、藤蔓这类方块会等它要贴的方块放好再放，不会贴错面。沙子、沙砾、铁砧等下落方块会等下面放上正确的方块。
- **大箱子和潜影盒。** 大箱子的第二半等第一半出现后再放，两半能正常合并。投影里的潜影盒只用内容物与投影完全一致的潜影盒来放（空盒对空盒）；创造模式下直接生成带对应内容的潜影盒。
- **告示牌和可交互方块。** 在投影有告示牌的位置放告示牌时，正面文字直接从投影填入，不会弹出编辑界面。对着箱子、告示牌、按钮、音符盒等可交互方块放方块时会自动潜行，不会误开、误按。

### 红石和铁轨

- **侦测器安全放置。** 开启“侦测器安全放置”（默认开）时，只有不会误触发机器的侦测器才会放；放不了的就故意跳过。会被侦测器触发的活塞，也会等侦测器盯着的那个方块放好再放。
- **铁轨安全放置。** 每放一节铁轨之前，先按原版铁轨连接规则模拟一遍：只有这节会变成投影里的形状、不会把已经放好的铁轨拉歪、也不会因为没有支撑而掉落时才放。互相连着的一组铁轨会预先规划放置顺序。原版怎么排都放不对的铁轨会留空，绝不会先放错再敲掉。
- **可调方块一次点一下，等确认再点。** 音符盒调音、中继器调延迟、比较器切模式、木门 / 活板门 / 栅栏门的开关、拉杆的开关，每点一下都要等服务器确认上一下，延迟再高也不会点过头。
- **可叠加方块。** 蜡烛、海泡菜、雪层、花簇和双层台阶会补到投影里的数量。
- **从下往上。** 开着分层打印时，机器从底部开始搭，上层的活塞、侦测器不会比下面的部分先放。

### 大型建筑

- **分层打印，从下往上**（默认开）。只打印工作范围内最低的、还没完成的那一层，这层完成后才开始上一层。每层完成时会提示结果：全部放好，或者正确、状态错误、方块错误、缺失各有多少。如果某一层暂时完成不了（例如材料用完了），几秒后会先往上打，到顶后再回来补。
- **超大工作半径。** 半径最大可设到 4096。只扫描半径内的投影部分，整段都是空气的区块段直接跳过，所以半径本身不会增加工作量。实际能放多远仍受服务器触及距离限制；单人世界里打印机会把触及距离调高，最多到原版上限 64。
- **单人世界自动调高交互距离。** 允许作弊时，打印机会用 `/attribute` 指令把你的方块交互距离调到和工作半径一致（原版上限 64），并等指令生效后才开始。单人世界里打印机不会在游戏不接受的距离外工作。
- **进度显示。** 打开“显示工作状态”后，准星下方会显示进度条：范围内已放好的方块数、状态错误、方块错误和当前层。“缺失材料 HUD”（默认开）会列出打印机找不到的材料。

### 材料和背包

- **使用容器时暂停**（默认开）。从你右键箱子或其他有界面的方块、村民、马或运输矿车的那一刻起，直到界面关闭后稍等片刻，以及打开任何背包 / 容器界面期间，打印机都会暂停（所有模式）。去箱子里拿材料不会再导致放错方块。
- **潜影盒补货**（默认开）。某种材料用完时，打印机会在背包里找装着它的潜影盒，在后台打开（不会弹出界面）并把材料取出来。支持 Advanced Shulkerboxes 和 QuickShulker；装了 AxShulkers 插件的服务器把潜影盒来源改成“插件”。背包快满时，会先把暂时用不到的物品放回去，优先放进已经装着同种物品的潜影盒。
- **快捷栏处理。** 材料会被换到 Litematica 设置 *pickBlockableSlots* 允许的快捷栏格子里。每次开始打印时，打印机会和服务器重新同步当前选中的快捷栏格子，这样即使有模组拦截“重复”的切换格子包，也不会拿错物品。

### 挖掘、填充和破基岩

- **内置自动换工具。** 打印机要破坏方块前，会从整个背包里挑最快、并且能让方块掉落的工具。不需要 Tweakeroo，也不影响你自己手动挖。
- **工具耐久保护。** 要用的工具耐久只剩 10 点或更少时，打印机不会换个差的工具凑合，而是停止所有破坏，把手上换成不会损坏的物品并提示你。修好或换掉工具后，关一下再开打印机即可继续。
- **秒破优先。** 挖掘模式先挖所有能秒破的方块，挖不动的留到最后；走动后范围里又出现能秒破的，会重新优先。默认只在你有效率 V 工具并且有急迫 II 时启用。
- **填充。** 在 Litematica 选区内，把空气、流体和可覆盖的方块填成列表里的方块（默认圆石）或手上拿的方块。可以设置朝向，例如放上半砖。
- **排流体。** 在选区内往水和岩浆里放方块（默认沙子），默认包括流动的液体。
- **挖掘。** 挖掉选区内所有非液体方块，可以用白名单 / 黑名单，或者 Tweakeroo 的破坏限制列表。
- **破基岩。** 把选区内的基岩交给破基岩模组处理：Fabric-Bedrock-Miner（LXYan2333）、Bedrock Miner（bunnyi116）或 BlockMiner。“自动”会优先用 LXYan2333 的。末地传送门框架、强化深板岩等方块可以加进方块列表；使用 Fabric-Bedrock-Miner 或 Bedrock Miner 时，这些方块还会自动同步到该模组自己的允许列表。默认忽略 Litematica 的渲染层，处理整个选区。

### 流体

- **用桶打印流体（默认关）。** 打开“用桶打印流体”后，会用水桶 / 岩浆桶放投影里的水源和岩浆源，并给应该装满水、岩浆或细雪的炼药锅倒满。只有整片水体四周和下方的方块都打印好之后才放水，不会漏出去。生存模式每格水源消耗一个装满的桶。
- **破冰放水**（默认关）。经典的生存方法：放冰、敲掉冰得到水，再把含水方块放进水里。

### 延迟高的服务器

- **关键操作等服务器确认。** 打印机对某一格做了操作后，服务器回应之前不会再动这一格，所以延迟不会导致同一格放两次。这也让它能和“等服务器确认后才显示方块”的客户端模组一起用，例如 Meteor 的 NoGhostBlocks（防幽灵方块）。
- **小心换物品。** 生存模式下从背包里换物品时，会等之前的操作都被确认；一组物品快用完时，会等服务器发来的剩余数量。
- **延迟过大暂停。** “延迟检测”（默认开）在服务器大约一秒没有发来任何数据时暂停打印机，数据恢复后继续。单人世界里也有效。
- **能用时就用轻松放置协议。** 单人游戏或装了 Servux 的服务器上，新方块直接按投影的状态放，不需要转头；其他服务器改为发送视角。Carpet 的旧协议只有在 Litematica 设置里手动选择时才会用。
- **自动关闭。** 死亡或断开连接时自动关闭打印机（“自动关闭打印机”）。

### 其他

- 原木自动去皮、用活珊瑚代替失活珊瑚、堆肥桶自动填充、骨粉催熟农作物、破坏错误 / 多余方块、跳过列表和覆盖列表、方块高亮、数据包放置和挖掘，以及可选的工作范围形状和遍历顺序。
- 方块列表可以填方块 ID（`minecraft:stone`）、当前语言的名称、标签（`#minecraft:logs`）、包含匹配（`带釉陶瓦,c`），也支持拼音（全拼和首字母）。设置界面的搜索框同样支持拼音。
- 第一次启动时会自动导入旧版投影打印机（BiliXWhite 或 water2004 版）的设置。
- 同时装了 Autyism 的投影增强（ALE）时，每个方块列表都会多一个“选择方块…”按钮，可以用图形界面挑选方块。

## 截图

![带泳池和内饰的别墅，用分层打印打出](docs/images/printed-villa.png)

带泳池和内饰的别墅，用分层打印打出。

![打印机打出的中世纪风格旅店](docs/images/printed-tavern.png)

打印机打出的中世纪风格旅店（带内饰）。

![打印机打出的木结构庄园](docs/images/printed-manor.png)

打印机打出的木结构庄园。

![打印进行中：墙已经放好，屋顶还是投影的预览](docs/images/printing-in-progress.png)

生存模式下打印到一半：墙已经放好，屋顶还是投影的预览。

![完工的小木屋](docs/images/printed-cottage.png)

打印机打完后的同一间小木屋。每个方块都和投影一致，包括楼梯朝向、门和灯笼。

![打印机设置的“填充”分页](docs/images/settings-filling.png)

设置界面。每种模式都有自己的分页，这里是“填充”分页。

![“破基岩”分页](docs/images/settings-bedrock.png)

“破基岩”分页：选择使用哪个破基岩模组、要破哪些方块。

![破基岩方块列表的方块选择界面](docs/images/bedrock-block-picker.png)

装了 ALE 后，方块列表可以用图形界面选择方块。这里正在把末地传送门框架加进破基岩列表。

## 使用方法

### 默认按键

| 操作 | 默认按键 | 在哪里修改 |
|---|---|---|
| 开关打印机（“打印机开关”） | Caps Lock | 打印机设置 → 核心 |
| 打开打印机设置 | Z + Y（按住 Z 再按 Y） | 打印机设置 → 快捷键 |
| 轮换模式（打印 → 挖掘 → 填充 → 排流体 → 破基岩，每次只开一个） | 未设置 | 打印机设置 → 快捷键 |
| 关闭全部模式（关闭打印机和所有模式） | 左 Ctrl + G | 打印机设置 → 快捷键 |
| 单独开始 / 停止某个模式、开关分层打印或破冰放水 | 未设置 | 该选项所在分页里、选项旁边的快捷键按钮 |

**打印机开关**用来开、关打印机。打印机开着的时候，“启用”打开了的模式都会工作；一个模式都没开时打开打印机，会自动开启“打印”。各模式自己的快捷键：打印机关着时只开这个模式并开始工作，打印机开着时开 / 关这个模式。**轮换模式**换成下一个模式；“轮换模式时关闭打印机”默认开启，会顺便把打印机关掉，新模式不会自己开始干活。每按一次键，快捷栏上方都会提示结果。

### 打开设置界面

- 在世界里按 **Z + Y**。
- 打开 Litematica 主菜单（默认 **M**），点 **打印机设置**。
- 在 Mod Menu 里选中本模组，点设置按钮。
- 在任意 MaLiLib 设置界面右上角的模组下拉框里切换过来。

### 指令

本模组不添加任何指令。单人世界允许作弊时，“单人世界自动调高交互距离”会在需要时替你执行原版的 `/attribute` 指令。

### 打印投影

1. 像平常一样用 Litematica 加载并放置投影。
2. 带上材料，或者装着材料的潜影盒。创造模式下打印机会直接从创造物品栏取。
3. 站到建筑附近，按 **Caps Lock**。没有开别的模式时，打印机会以打印模式开始，在触及范围内从最低层开始放方块。它不会帮你移动，需要沿着建筑走，或者调大工作半径。
4. 再按一次 **Caps Lock** 停止。想看进度条，就在 **核心** 分页打开 **显示工作状态**。

打印机遵循 Litematica 的渲染层，所以可以用 Litematica 的图层控制只打印某几层。

### 站在原地打印大型建筑

1. 在允许作弊的单人世界里，把 **工作半径**（核心分页）设成例如 32 或 64。打印机会自动调高你的触及距离，最多到 64。
2. 保持 **分层打印** 开启，站在建筑中间附近按 Caps Lock。它会从最底层一层一层往上搭，每层完成都会提示。
3. 在服务器上，半径受服务器的触及距离限制。除非服务器允许更远，否则把工作半径保持为 `0`（即你正常的触及距离）。

### 填充、排流体或挖掘一片区域

1. 用 Litematica 的选区工具框选区域。
2. 打开对应的分页（**填充**、**排流体** 或 **挖掘**），检查方块列表。
3. 开始这个模式：按它自己的快捷键（在它的 **启用** 选项旁边设置），或者用 **轮换模式** 选中它再按 Caps Lock。

### 破基岩

1. 装一个受支持的破基岩模组，并在生存模式下使用（创造模式不能用破基岩模式）。
2. 用 Litematica 的选区工具框选区域。
3. 在 **破基岩** 分页把“破基岩模组”保持“自动”，或者手动选择。
4. 用 **启用破基岩** 的快捷键开始，或者用 **轮换模式** 选中破基岩再按 Caps Lock。破基岩模式运行期间打印机会打开破基岩模组，结束后恢复原状。

## 设置

下表的名称与游戏内显示一致。设置界面有一个“全部”分页，其余每个主题一个分页。

### 核心

| 选项 | 默认值 | 作用 |
|---|---|---|
| 打印机开关 | 关（Caps Lock） | 开、关打印机，见“默认按键”。 |
| 工作半径 | 0 | 打印机以你为中心的工作范围，最大 4096。`0` 表示使用你正常的触及距离。 |
| 单人世界自动调高交互距离 | 开 | 单人世界里打印机不会超出你的交互距离工作；允许作弊时，会把交互距离调到工作半径（最大 64）。 |
| 使用容器时暂停 | 开 | 打开或使用容器、背包界面时暂停。 |
| 显示工作状态 | 关 | 在准星下方显示进度。 |
| 缺失材料 HUD | 开 | 在 Litematica 信息 HUD 区域列出缺少的材料。 |
| 延迟检测 / 延迟检测最大值 | 开 / 20 tick | 服务器这么久没有发来数据时暂停。 |
| 工作范围形状 | 球体 | 工作范围的形状：球体、正方体或八面体。 |
| 遍历顺序 | X→Z→Y | 检查方块的顺序，每个轴都可以反向。 |
| 按方块类型分类 | 关 | 每轮只处理一种方块，减少换物品。 |
| 迭代时长限制 | 8 毫秒 | 每 tick 用来检查方块的最长时间。 |
| 自动关闭打印机 | 开 | 死亡或断开连接时关闭打印机。 |

### 快捷键

| 选项 | 默认值 | 作用 |
|---|---|---|
| 打开设置菜单 | Z + Y | 打开这个设置界面。 |
| 轮换模式 | 未设置 | 换成下一个模式，并关掉其他模式。没装破基岩模组时跳过破基岩。 |
| 轮换模式时关闭打印机 | 开 | 轮换模式时顺便关掉打印机，误按也不会让新模式自己开始干活。关闭后打印机保持原样。 |
| 关闭全部模式 | 左 Ctrl + G | 关闭打印机和所有模式。 |

### 打印

| 选项 | 默认值 | 作用 |
|---|---|---|
| 启用打印 | 关 | 打印模式，打印机开着时工作。一个模式都没开时打开打印机，会自动开启它。 |
| 分层打印（从下往上） | 开 | 先打印最低的未完成层。 |
| 选区类型 | 可见层 | “玩家下方 / 玩家上方”只处理你脚下以下或以上的部分；Litematica 的渲染层始终生效。 |
| 使用轻松放置协议 | 开 | 服务器支持时使用 Litematica 的轻松放置协议（单人游戏、装了 Servux 的服务器）。 |
| 侦测器安全放置 | 开 | 跳过会误触发机器的侦测器。 |
| 铁轨安全放置 | 开 | 只放会变成正确形状的铁轨。 |
| 音符盒自动调音 | 开 | 把音符盒调到投影里的音高。 |
| 用桶打印流体 | 关 | 用桶放水源、岩浆源，并倒满炼药锅。 |
| 破冰放水 | 关 | 放冰再敲掉来得到水（仅生存模式）。 |
| 凭空放置 | 开 | 没有可点击的相邻方块也能放；需要支撑的方块仍会等支撑。 |
| 覆盖打印 - 开关 / 列表 | 开 / 雪、水、岩浆、气泡柱、矮草丛 | 列表里的方块直接被替换。 |
| 跳过放置 - 开关 / 列表 | 关 / 空 | 列表里的方块永远不放。 |
| 跳过含水方块 | 关 | 跳过投影里含水的方块。 |
| 破坏错误方块 / 破坏多余方块 | 关 | 破坏与投影不符的方块，或投影里没有的方块。 |
| 破坏错误状态方块(实验性) | 关 | 破坏方块对了但状态不对、又无法通过点击修正的方块。 |
| 始终潜行 | 关 | 每次放置都潜行。 |
| 使用快捷潜影盒 | 开 | 从背包里的潜影盒补充缺少的材料。 |
| 潜影盒来源 | 模组（Quick Shulker） | “模组”：使用已安装的 Advanced Shulkerboxes 或 QuickShulker；“插件”：装了 AxShulkers 的服务器。 |
| 背包满时有序放回潜影盒 | 开 | 背包满时把物品放回潜影盒。 |

“打印”分页最后是放置相关的设置，填充和排流体也共用：

| 选项 | 默认值 | 作用 |
|---|---|---|
| 放置使用数据包 | 关 | 放置时不做客户端预测，在严格的服务器上可能有帮助。 |
| 放置间隔时间 | 1 tick | 每轮放置之间隔多少 tick。打印红石机器时不建议设为 0。 |
| 每刻放置方块数 | 1 | 每轮放几个方块，`0` 表示不限制。 |
| 放置冷却时间 | 3 tick | 同一格重试前等多久，不要设为 0。 |
| 下落方块检查 | 开 | 沙子、沙砾、铁砧等会等下面的方块放对再放。 |

### 破坏方块

| 选项 | 默认值 | 作用 |
|---|---|---|
| 自动切换工具 | 开 | 破坏前从背包里选最合适的工具。 |
| 工具耐久保护 / 耐久保护阈值 | 开 / 10 | 工具耐久不高于该值时停止破坏，而不是继续用它。 |
| 检查方块硬度 | 开 | 跳过无法破坏的方块。 |
| 挖掘进度阈值 | 100% | 挖到这个进度（70%~100%）就算挖完。原版服务器在 70% 就认可，但反作弊不一定。 |
| 即时挖掘 | 关 | 一 tick 的挖掘进度达到上面的阈值时直接破坏；阈值低于 100% 时才有作用。 |
| 破坏间隔时间 / 每刻破坏方块数 / 挖掘冷却时间 | 1 tick / 1 / 3 tick | 破坏速度限制。 |
| 破坏限制规则来源 / 模式 / 白名单 / 黑名单 | 自定义 / 无限制 | 打印机到底能破坏哪些方块（挖掘模式，以及打印时破坏错误或多余的方块），或使用 Tweakeroo 的列表。 |

### 挖掘、填充、排流体

| 选项 | 默认值 | 作用 |
|---|---|---|
| 启用挖掘 | 关 | 挖掉选区内的方块。 |
| 仅在效率V+急迫II时启用秒破优先 | 开 | 只在有效率 V 和急迫 II 时秒破优先；关闭则始终秒破优先。 |
| 挖掘限制规则来源 / 模式 / 白名单 / 黑名单 | 自定义 / 无限制 | 只对挖掘模式生效的额外限制，破坏限制同样有效。 |
| 启用填充 | 关 | 填充选区。 |
| 填充方块模式 / 方块列表 | 方块列表 / 圆石 | 用列表里的方块填充，或用手上拿的方块（“手持物品”）。 |
| 填充方块朝向 | 无 | 填充方块的朝向，例如上半砖或下半砖。 |
| 启用排流体 | 关 | 替换选区内的流体。 |
| 包含流动液体 | 开 | 流动的水和岩浆也会被填掉。 |
| 流体填充方块列表 / 流体列表 | 沙子 / 水、岩浆 | 用来填的方块，以及要排掉的流体。 |

### 破基岩

| 选项 | 默认值 | 作用 |
|---|---|---|
| 启用破基岩 | 关 | 把选区内的方块交给破基岩模组。 |
| 破基岩模组 | 自动 | 使用哪个模组。自动：先 Fabric-Bedrock-Miner（LXYan2333），其次 Bedrock Miner（bunnyi116），再其次 BlockMiner。 |
| 忽略渲染层 | 开 | 处理整个选区，不受 Litematica 渲染层限制。 |
| 破基岩方块列表 | `minecraft:bedrock` | 要破的方块，也会加入 Fabric-Bedrock-Miner 或 Bedrock Miner 的允许列表。 |

### 高亮显示

| 选项 | 默认值 | 作用 |
|---|---|---|
| 启用方块高亮 | 关 | 用轮廓标出打印机放置（白）、调整（绿）、破坏（红）或放置失败（灰）的方块。 |
| 高亮样式 / 渐隐时长 / 透视模式 | 轮廓 / 0.5 秒 / 关 | 高亮的外观，颜色可以自定义。 |

## 前置要求

每个 Minecraft 版本有单独的 jar：

| Minecraft | jar 文件 | Java | Fabric Loader | MaLiLib | Litematica |
|---|---|---|---|---|---|
| 1.21.11 | `litematica-printer-autyism-1.0.0.jar` | 21 | 0.17.0 或更高 | 0.27.0 或更高 | 0.26.0 或更高 |
| 26.1、26.1.1、26.1.2 | `litematica-printer-autyism-1.0.0+26.1.2.jar` | 25 | 0.19.3 或更高 | 0.28.12 或更高 | 0.27.14 或更高 |

[Fabric API](https://modrinth.com/mod/fabric-api)、[MaLiLib](https://modrinth.com/mod/malilib) 和 [Litematica](https://modrinth.com/mod/litematica) 都是必需的，各自下载你的 Minecraft 版本对应的那一版。

可选：

- [Mod Menu](https://modrinth.com/mod/modmenu)：在模组列表里提供设置按钮。
- Autyism 的投影增强（ALE）：所有方块列表都能用图形界面选择方块。
- [Tweakeroo](https://modrinth.com/mod/tweakeroo)：打印机可以使用它的破坏限制列表。
- [Advanced Shulkerboxes](https://modrinth.com/mod/advanced-shulkerboxes) 或 QuickShulker（模组 ID `quickshulker`）：潜影盒补货。客户端和服务器都要装。
- AxShulkers（服务器插件）：在使用它的服务器上补货。
- 服务器装 [Servux](https://modrinth.com/mod/servux)：多人游戏也能用轻松放置协议。
- 破基岩模式需要一个破基岩模组：[Fabric-Bedrock-Miner](https://modrinth.com/mod/fabric-bedrock-miner)（LXYan2333）、[Bedrock Miner](https://modrinth.com/mod/next-fabric-bedrock-miner)（bunnyi116）或 [BlockMiner](https://github.com/z7087/blockminer)。

**安装端：** 仅客户端。服务器不需要装本模组，只有上面的部分可选联动需要服务器端。

## 兼容性

- **其他打印机：** 不要和其他 Litematica Printer（BiliXWhite 版、water2004 版、aleksilassila 原版）同时安装。和它们同时安装时游戏会拒绝启动（模组 ID 为 `litematica-printer` 或 `litematica_printer`）。
- **Sodium 和 Iris：** 没有已知问题，打印机在同时装了两者的 1.21.11 整合包里测试过。
- **Tweakeroo：** 可以一起用。打印机的自动换工具是自己实现的，不依赖 Tweakeroo 的换工具功能。
- **Meteor Client：** 开着 NoGhostBlocks（防幽灵方块）时打印正常。但 Bedrock Miner（bunnyi116）在 NoGhostBlocks 开着时什么都破不掉，打印机会给出提示；请关掉 NoGhostBlocks，或改用 Fabric-Bedrock-Miner（LXYan2333）。
- **Carpet：** Carpet 的轻松放置协议（V2）只有在 Litematica 设置里手动选择 V2 时才会用，因为它需要服务器打开 `accurateBlockPlacement` 规则。
- **ALE：** 可选，两个模组互不依赖。

## 安装

1. 为你的 Minecraft 版本安装 Fabric Loader（需要的版本和 Java 见上表）。
2. 下载这个 Minecraft 版本对应的 Fabric API、MaLiLib、Litematica，以及本模组对应这个版本的 jar。
3. 把所有 jar 文件放进 `mods` 文件夹。
4. 删掉文件夹里其他的 Litematica Printer jar。
5. 启动游戏，进入世界，按 **Z + Y** 打开设置。

## 常见问题

**按了 Caps Lock 没反应。**

看一下快捷栏上方的提示：它会说明打印机有没有开、哪些模式在工作。如果开着的是挖掘之类的别的模式，按轮换模式或打印的快捷键换回来。确认投影已放置并且在工作半径内，Litematica 的渲染层也显示着你所在的那部分。打开容器或背包界面时、服务器卡顿时打印机也会暂停。缺材料时，缺失材料 HUD 会列出来。

**有些侦测器或铁轨没放，是 bug 吗？**

不是。会误触发机器的侦测器、放不成正确形状的铁轨，都是故意留空的。等机器其他部分完成后手动补上即可。可以关掉“侦测器安全放置”，但这样侦测器可能在建造过程中触发你的机器。

**能不能站着不动打印大型建筑？**

在允许作弊的单人世界里可以：调大工作半径，保持分层打印开启。在服务器上只能在服务器允许的触及距离内打印。

**单人世界打印完后，我的触及距离还是很远。**

“单人世界自动调高交互距离”用原版 `/attribute` 指令修改了你在这个世界里的方块交互距离，这个值会一直保留。想恢复的话执行 `/attribute @s minecraft:block_interaction_range base set 4.5`。不想要这个功能就把该选项关掉。

**能在服务器上用吗？**

本模组只装在客户端，服务器不需要安装，但很多服务器不允许使用打印机，请先看清规则。打印机会发送自己的视角并快速放置方块，反作弊可能会注意到。如果放置经常被拒绝，调大“放置间隔时间”，“每刻放置方块数”保持 1，“工作半径”保持 0。服务器装了 Servux 时，大部分转头都可以省掉。

**为什么只用了快捷栏的部分格子？**

打印机使用 Litematica 设置 *pickBlockableSlots* 里列出的格子（默认 1~5）。如果这个列表是空的，它就无法从背包里拿物品。

**水没有被打印。**

“用桶打印流体”默认是关的。打开它，并带上装满的桶。

**破基岩模式没反应。**

破基岩模式只能在生存模式下用，需要装受支持的破基岩模组，并且使用 Litematica 的选区（不是投影）。另见“兼容性”里关于 Meteor 的说明。

发现 bug？欢迎提交 issue：https://github.com/Autyism/litematica-printer-autyism/issues

## 已知限制

- **故意留空：** 会误触发机器的侦测器（开着侦测器安全放置时）、原版怎么放都放不成正确形状的铁轨（例如上下紧贴的一串上坡动力铁轨），以及依赖这些方块的东西。
- **不会打印：** 实体（物品展示框、盔甲架、画、矿车）、睡莲、气泡柱、活塞头，以及流动的水和岩浆（它们由水源、岩浆源自然产生）。含水方块会被放成不含水的，除非开启“破冰放水”（仅生存模式）。
- **由游戏决定的状态：** 红石充能、亮灭、树叶距离、树苗生长阶段、阳光探测器的信号强度等不会被还原，在进度显示里可能算作“状态错误”。
- **用桶打印流体：** 打印机需要能看到相邻方块的某个面，所以有些水源要等你走动后才会放。如果水源还没放就被上一层盖住了，需要手动放。
- **多人游戏：** 工作半径受服务器触及距离限制；自动调高交互距离只在单人世界有效；潜影盒补货需要服务器装对应的模组或插件；轻松放置协议需要服务器装 Servux。
- **生存模式材料种类多：** 材料种类比可用的快捷栏格子多时，在延迟高的服务器上打印会变慢，因为每次从背包换物品都要等服务器确认。
- **Bedrock Miner（bunnyi116）和 Meteor 的 NoGhostBlocks** 不能同时使用。
- **翻译：** 英文和简体中文是完整的。繁体中文、文言文和俄语里，较新的选项会显示简体中文或英文。

## 致谢

本模组延续了以下作者的工作：

- [aleksilassila](https://github.com/aleksilassila/litematica-printer)：最初的 Litematica Printer
- [zhaixianyu](https://github.com/zhaixianyu/litematica-printer)：原版的二改分支
- [BiliXWhite](https://github.com/BiliXWhite/litematica-printer)（BlinkWhite）：本版本所基于的分支
- [water2004](https://github.com/water2004/litematica-printer)：其修复已合并进本版本
- [bunnyi116](https://github.com/bunnyi116)：上游贡献者，Bedrock Miner 的作者
- 以及这些项目中致谢的所有人，包括 Rofumer、Cjsah、EnderPhantomWing 和 MoRanpcy

同样感谢 masa（Litematica、MaLiLib、Tweakeroo）、LXYan2333（Fabric-Bedrock-Miner）、z7087（BlockMiner）、Max Henkel（Advanced Shulkerboxes）、kyrptonaught 和 MoRanpcy（QuickShulker），以及 pinyin4j（已内置，用于拼音搜索）。

## 许可证

GNU Affero 通用公共许可证 v3.0（[AGPL-3.0](LICENSE.md)），继承自上游 Litematica Printer 项目：你可以使用、分享和修改本模组，但你的版本必须以同样的许可证公开源代码。
