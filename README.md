<p align="center"><img src="docs/icon.png" width="128" alt="icon"></p>
<h1 align="center">Litematica Printer Autyism Edition</h1>
<p align="center">A standalone Litematica printer for Minecraft 1.21.11 that builds schematics from the bottom up and takes care with redstone, rails and laggy servers.</p>

**English** | [简体中文](README.zh-CN.md)

[![Minecraft 1.21.11](https://img.shields.io/badge/Minecraft-1.21.11-62B47A)](https://www.minecraft.net/) [![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4)](https://fabricmc.net/) [![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE.md)

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
| Turn the printer on or off (*Work Switch*) | Caps Lock | Printer settings, Hotkeys tab |
| Open the printer settings | Z + Y (hold Z, press Y) | Printer settings, Hotkeys tab |
| Close all modes (turns off every mode and the Work Switch) | Left Ctrl + G | Printer settings, Hotkeys tab |
| Cycle mode (Printing, Mining, Filling, Fluid Removal, Bedrock; one at a time) | not set | Printer settings, Hotkeys tab |
| Toggle one mode, Layered Printing or Break Ice for Water | not set | Hotkey button next to that option in its tab |

The **Work Switch** is the master switch: while it is on, every mode whose *Enable* option is on is working. A message above the hotbar confirms each toggle. *Cycle Mode* only switches modes; it does not turn on the Work Switch.

### Opening the settings

- Press **Z + Y** while in a world.
- Open Litematica's main menu (**M** by default) and click **Printer Settings**.
- In Mod Menu, select Litematica Printer Autyism Edition and click the settings button.
- Use the mod drop-down at the top right of any MaLiLib settings screen.

### Commands

The mod adds no commands. In singleplayer with cheats allowed, *Auto Raise Reach* runs the vanilla `/attribute` command for you when needed.

### Print a schematic

1. Load and place a schematic with Litematica as usual.
2. Open the printer settings, go to the **Printing** tab and set **Enable Printing** to `true`. This is off by default and only needs to be done once.
3. Carry the materials, or shulker boxes that contain them. In creative mode the printer takes them from the creative inventory.
4. Stand near the build and press **Caps Lock**. The printer places blocks within reach, lowest layer first. It never moves you; walk along the build or use a larger work radius.
5. Press **Caps Lock** again to stop. For a progress bar, turn on **Show Work Status** in the Core tab.

The printer follows Litematica's render layers, so you can limit printing to certain layers with Litematica's layer controls.

### Print a big build from one spot

1. In singleplayer with cheats allowed, set **Work Radius** (Core tab) to, for example, 32 or 64. The printer raises your reach automatically, up to 64.
2. Keep **Layered Printing** on, stand near the middle of the build and press Caps Lock. It builds layer by layer from the bottom and reports each finished layer.
3. On a server, the radius is limited by the server's reach. Leave Work Radius at `0` (your normal reach) unless the server allows more.

### Fill, remove fluids or mine an area

1. Select the area with Litematica's area selection tool.
2. Open the matching tab (**Filling**, **Fluid Removal** or **Mining**), check its block lists and set its **Enable** option to `true`. Turn Enable Printing off if you only want this mode.
3. Press Caps Lock.

### Break bedrock

1. Install a supported bedrock-breaking mod and play in survival (bedrock mode does not work in creative).
2. Select the area with Litematica's area selection tool.
3. In the **Bedrock** tab, set **Enable Bedrock Breaking** to `true`. Keep **Bedrock Miner Backend** on *Auto* or choose a mod.
4. Press Caps Lock. The printer turns the bedrock miner on while bedrock mode runs and restores it afterwards.

## Settings

Names are shown as they appear in game. The settings screen has an **All** tab and one tab per topic.

### Core

| Option | Default | What it does |
|---|---|---|
| Work Switch | off (Caps Lock) | Master switch for all modes. |
| Work Radius | 0 | How far from you the printer works, up to 4096. `0` uses your normal reach. |
| Auto Raise Reach (Singleplayer) | on | With cheats allowed, raises your block interaction range to the work radius (max 64). |
| Limit Range to Reach (Singleplayer) | on | In singleplayer, never works beyond the range the game accepts. |
| Pause While Using Containers | on | Pauses while you open or use a container or inventory screen. |
| Show Work Status | off | Progress display under the crosshair. |
| Missing Material HUD | on | Lists missing materials in Litematica's info HUD area. |
| Lag Detection / Lag Detection Max | on / 20 ticks | Pauses when the server has sent nothing for this long. |
| Work Area Shape | Sphere | Shape of the work area: Sphere, Cube or Octahedron. |
| Iteration Order | X→Z→Y | Order in which positions are checked; each axis can be reversed. |
| Classify by Block Type | off | Handles one block type per pass to reduce item switching. |
| Iteration Time Limit | 8 ms | Maximum time per tick spent checking positions. |
| Auto Disable Printer | on | Turns the Work Switch off when you die or disconnect. |

### Placing Blocks

| Option | Default | What it does |
|---|---|---|
| Placement Interval Time | 1 tick | Ticks between placement rounds. Not recommended at 0 for redstone. |
| Blocks to Place Per Tick | 1 | Placements per round; `0` means no limit. |
| Placement Cooldown Time | 3 ticks | Wait before retrying the same spot. Do not set it to 0. |
| Placement Using Packets | off | Places without client-side prediction; can help on strict servers. |
| Falling Block Check | on | Sand, gravel, anvils and the like wait for the right block below. |

### Printing

| Option | Default | What it does |
|---|---|---|
| Enable Printing | off | Turns the printing mode on. |
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

### Breaking Blocks

| Option | Default | What it does |
|---|---|---|
| Auto Tool Switch | on | Picks the best tool from your inventory before breaking. |
| Tool Durability Protection / Durability Threshold | on / 10 | Stops breaking instead of using a tool at or below this durability. |
| Check Block Hardness | on | Skips unbreakable blocks. |
| Mining Progress Threshold | 100 % | Counts a block as broken at this progress (70–100 %). The vanilla server accepts 70 %, but anti-cheat may not. |
| Instant Mining | off | Breaks a block in one tick when one tick of progress reaches the threshold above; only matters below 100 %. |
| Breaking Interval Time / Blocks to Break Per Tick / Mining Cooldown Time | 1 tick / 1 / 3 ticks | Breaking speed limits. |
| Mining Limit Rule Source / Custom Limit Mode | Custom / no limit | Whitelist or blacklist for the printer's breaking, or Tweakeroo's lists. |

### Mining, Filling, Fluid Removal

| Option | Default | What it does |
|---|---|---|
| Enable Mining | off | Breaks blocks in the area selection. |
| Instant-First Only With Eff V + Haste II | on | Instant-first mining only with Efficiency V and Haste II; off = always. |
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

| | Version | Notes |
|---|---|---|
| Minecraft | 1.21.11 | Java Edition |
| Java | 21 | |
| Fabric Loader | 0.17.0 or newer | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | for 1.21.11 | required |
| [MaLiLib](https://modrinth.com/mod/malilib) | 0.27.0 or newer | required |
| [Litematica](https://modrinth.com/mod/litematica) | 0.26.0 or newer | required |

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
- **Sodium and Iris:** no known problems; the printer was tested in a modpack that uses both.
- **Tweakeroo:** works alongside it. The printer's tool switching is its own and does not need Tweakeroo's.
- **Meteor Client:** printing works with NoGhostBlocks on. Bedrock Miner (bunnyi116) cannot break anything while NoGhostBlocks is on; the printer shows a warning. Turn NoGhostBlocks off or use Fabric-Bedrock-Miner (LXYan2333).
- **Carpet:** Carpet's Easy Place protocol (V2) is only used if you select Version 2 in Litematica's settings, because it needs the server's `accurateBlockPlacement` rule.
- **ALE:** optional; neither mod needs the other.

## Installation

1. Install Fabric Loader 0.17.0 or newer for Minecraft 1.21.11 (Java 21).
2. Download Fabric API, MaLiLib and Litematica for 1.21.11, and this mod.
3. Put all the jar files into your `mods` folder.
4. Remove any other Litematica Printer jar from the folder.
5. Start the game, join a world and press **Z + Y** to open the settings.

## FAQ

**I pressed Caps Lock and nothing happens.**

Check that **Enable Printing** is `true` (it is off by default), that a schematic is placed within your work radius, and that Litematica's render layers show the part you are standing at. The printer also pauses while a container or inventory screen is open and while the server is lagging. If materials are missing, the Missing Material HUD lists them.

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

Thanks also to masa (Litematica, MaLiLib, Tweakeroo), LXYan2333 (Fabric-Bedrock-Miner), z7087 (BlockMiner), Max Henkel (Advanced Shulkerboxes), kyrptonaught and MoRanpcy (QuickShulker), and pinyin4j (bundled, for pinyin search). The mod icon comes from the BiliXWhite fork.

## License

GNU Affero General Public License v3.0 ([AGPL-3.0](LICENSE.md)), inherited from the upstream Litematica Printer projects: you may use, share and change the mod, as long as the source of your version stays available under the same license.
