**In short**

- Builds your loaded Litematica schematic for you, bottom layer first.
- Redstone, rails, doors and stairs come out facing the right way.
- Made for laggy servers: it waits for the server instead of guessing.
- Restocks from shulker boxes; can also fill areas, drain fluids and break bedrock.
- Caps Lock turns it on and off, Z + Y opens the settings.

Litematica Printer Autyism Edition places the blocks of your loaded Litematica schematic around you automatically. It can also fill an area, remove water and lava, mine a selection and break bedrock.

It is a standalone mod (not an add-on), based on the BiliXWhite and water2004 forks of Litematica Printer and reworked so that what it places matches the schematic, also on real servers with lag. It replaces other Litematica Printer builds; do not install both.

## Features

### New in this edition

Compared with the 1.21.11 printer builds it is based on, this edition adds or reworks:

- Layered printing from the bottom up, a work radius of up to 4096 and automatic reach in singleplayer.
- A print report when it is done: what is still not right and why (missing material, safe observer rules, wrong blocks...), and a hotkey that takes you to those blocks one by one.
- A pause while you use containers, so taking materials from a chest no longer causes wrong blocks.
- Fixed placement of doors (hinge side), vines, levers, buttons and double chests, signs that can be placed against other signs, and server-confirmed head turns for pistons, observers and other direction-sensitive blocks.
- Safe rail placement, and redstone adjustments (note blocks, repeaters and more) that wait for the server between clicks.
- Waiting for the server where it matters, so lag or Meteor's NoGhostBlocks cannot cause double placements; the lag pause now also works in singleplayer.
- Shulker box restocking through Advanced Shulkerboxes, and shulker boxes printed only with matching contents.
- Built-in tool switching with durability protection, and instant-first mining.
- Bedrock mode breaks bedrock itself (no bedrock mod needed), many blocks at once; LXYan2333's Fabric-Bedrock-Miner and the other bedrock mods can still be used.
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
- **Exact rails with ALE.** When ALE 1.2.0 or newer is installed on your client and on the server (or in single player), every rail goes down directly in the schematic's shape: ramps and curves that vanilla's rules cannot place safely are no longer left empty, and rails that are already placed are never bent. Without ALE on the server the printer uses safe rails as above.
- **Adjustable blocks, one confirmed click at a time.** Note blocks are tuned, repeaters set to their delay, comparators to their mode, wooden doors, trapdoors and fence gates opened or closed and levers flipped. Each click waits until the server has confirmed the previous one, so lag cannot make it overshoot. With *Finish Adjusting First* (on by default) such a block is finished before the next block is placed.
- **Stackable blocks.** Candles, sea pickles, snow layers, flower beds and double slabs are built up to the count in the schematic.
- **Bottom-up order.** With layered printing, machines are built from the ground up, so upper pistons and observers are not placed before the parts beneath them.

### Big builds

- **Layered printing, bottom-up** (on by default). Only the lowest unfinished layer is printed; the next layer starts when it is done. By default that means the whole layer in loaded chunks, so with a small range the printer waits until you walk over to the rest (*Layer Counts as Done*). A message reports every finished layer: either all blocks placed, or how many are correct, in the wrong state, the wrong block, or missing. If some blocks of a layer can't be placed (for example, materials ran out), the printer stops waiting for them after a few seconds and comes back to them after reaching the top.
- **Large work radius.** The radius can be set up to 4096. Only the part of the schematic inside it is scanned, and completely empty sections are skipped, so the radius itself does not add work. The server's reach still limits what can be placed; in singleplayer the printer raises your reach up to the vanilla maximum of 64.
- **Automatic reach in singleplayer.** With cheats allowed, the printer raises your block interaction range to match the work radius (vanilla limit 64) with the `/attribute` command, and waits until it applies before starting. In singleplayer it never works beyond the range the game accepts.
- **Progress display.** Turn on *Show Work Status* for a progress bar under the crosshair: blocks placed in the whole schematic (or only within range, see *Progress Counts*), wrong states, wrong blocks and the current layer. The *Missing Material HUD* (on by default) lists materials the printer could not find.

### Materials and inventory

- **Pauses while you use containers** (on by default). From the moment you right-click a chest or another block with a screen, a villager, a horse or a storage minecart until shortly after the screen closes, and while any inventory screen is open, the printer pauses (all modes). Taking materials out of a chest no longer leads to wrong blocks.
- **Shulker box restocking** (on by default). When a material runs out, the printer finds a shulker box in your inventory that holds it, opens it in the background (no screen appears) and takes the stack out. This uses Advanced Shulkerboxes or QuickShulker; on servers with the AxShulkers plugin, set the source to *Plugin*. When your inventory is nearly full, it first puts away items you do not need right now, preferably into a box that already holds them.
- **Hotbar handling.** Materials are moved into the hotbar slots allowed by Litematica's *pickBlockableSlots* setting, several at once when a pass needs more than one, into empty slots first and then the slot the printer used longest ago. When printing starts, your selected hotbar slot is re-synced with the server, so mods that filter "duplicate" slot packets cannot make it place the wrong item.

### Breaking, filling and bedrock

- **Built-in tool switching.** Before the printer breaks a block, it picks the fastest suitable tool from your whole inventory, preferring tools that make the block drop. Tweakeroo is not needed, and your own mining is not affected.
- **Tool durability protection.** If the tool it would use has 10 durability or less left, all breaking stops instead of falling back to a worse tool, a non-breakable item goes into your hand and a warning appears. Repair or replace the tool, then turn the printer off and on. In bedrock mode it also checks the pickaxe the bedrock mod will use.
- **Instant-first mining.** Mining mode breaks everything it can break instantly first and leaves slow blocks for last; walking on brings new instant blocks to the front. By default this only happens when you have an Efficiency V tool and Haste II.
- **Filling.** Fills air, fluids and overwritable blocks inside your Litematica area selection with a block from a list (default: cobblestone) or the block in your hand. You can choose a facing, for example to place top slabs.
- **Fluid removal.** Places blocks (default: sand) into water and lava sources inside the area selection; the flowing fluid then drains away by itself. Turn off *Sources Only* to fill flowing fluid too.
- **Mining.** Breaks every non-liquid block inside the area selection, with an optional whitelist or blacklist, or Tweakeroo's block-breaking restriction lists.
- **Bedrock breaking.** Breaks every bedrock block in your area selection with pistons and redstone torches, the vanilla way: a piston pointing away from the block, a torch to push it out, then in one go the torch and piston are broken and a piston pointing at the bedrock is placed, which deletes the bedrock as it pulls back. Where nothing can hold the torch, a slime block goes under it. Up to *Targets at Once* blocks (default 12) go at the same time, laid out so no torch can power another block's piston. Every step waits for the server, and pistons, torches and slime blocks are always removed afterwards, also when you turn the printer off or walk away. You need pistons, redstone torches (slime blocks for lone blocks) and a pickaxe that breaks a piston in one hit (Efficiency III or more), standing on the ground. A bedrock floor goes at about 12–16 blocks per second. Add other blocks such as end portal frames or reinforced deepslate to the block list. By default it ignores Litematica's render layers and works on the whole selection. *Bedrock Backend* can instead hand the blocks to Fabric-Bedrock-Miner (LXYan2333), Bedrock Miner (bunnyi116) or BlockMiner; then blocks in progress are at least 5 apart and the list is synced to that mod's allow list.

### Fluids

- **Bucket printing (off by default).** Turn on *Print Fluids With Buckets* to place schematic water and lava sources with buckets and to fill cauldrons that should hold water, lava or powder snow. A source is only placed after the walls and floor around the whole body of fluid are printed, so nothing leaks. In survival, each source uses one filled bucket.
- **Break Ice for Water** (off by default). The classic survival method: place ice, break it, then place waterlogged blocks into the water.
- **Print Waterlogged Blocks** (off by default). Pours water into waterlogged blocks, only where it can't leak out.

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

1. Play in survival (bedrock mode does not work in creative) with pistons, redstone torches, a few slime blocks and a pickaxe with Efficiency III or more.
2. Select the area with Litematica's area selection tool.
3. In the **Bedrock** tab, keep **Bedrock Backend** on *Auto* (the printer breaks it itself) or choose a bedrock mod.
4. Start bedrock mode with the hotkey of **Enable Bedrock Breaking**, or select it with **Cycle Mode** and press Caps Lock. With a bedrock mod chosen, the printer turns that mod on while bedrock mode runs and restores it afterwards.

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
| Progress Counts | Whole Schematic | What the progress bar counts while printing: the whole schematic in loaded chunks, or only blocks within range. |
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
| Cycle Mode | not set | Switches to the next mode and turns the others off. With a bedrock mod chosen as the backend, Bedrock is skipped when that mod isn't installed. |
| Cycle Mode Turns Printer Off | on | Cycle Mode also turns the printer off, so a key pressed by accident never starts a mode. Off: the printer stays as it is. |
| Close All Modes | Left Ctrl + G | Turns the printer and every mode off. |

### Printing

| Option | Default | What it does |
|---|---|---|
| Enable Printing | off | Printing mode; it works while the printer is on. Turning the printer on with no mode enabled enables it. |
| Layered Printing (bottom-up) | on | Prints the lowest unfinished layer first. |
| Layer Counts as Done | Whole Layer | When a layer is finished: the whole layer in loaded chunks, or only the part within range. Layer messages follow it. |
| Hide Layer Messages | off | No chat message after each layer. |
| Finish Adjusting First | on | Finishes a block that needs clicks (repeater delay, note pitch...) before placing the next one. Off: places everything first and adjusts afterwards. |
| Print Waterlogged Blocks | off | Pours water into waterlogged blocks, only where it can't leak out. |
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
| Placement Using Packets | off | Places without client-side prediction; can help on strict servers. The printer also does this by itself when another mod turns the game's block prediction off (Meteor's NoGhostBlocks, for example). |
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
| Instant Mining | off | Breaks a block in one tick when one tick of progress reaches the threshold above; only matters below 100 %. With ALE 1.2.0 on the server too (or in single player), deepslate also breaks in one tick from 50 % (Efficiency V netherite pickaxe and Haste II). |
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
| Sources Only | on | Fills only water and lava sources; the flowing fluid drains away by itself. |
| Fluid Filling Block List / Fluid List | sand / water, lava | Blocks used, and fluids removed. |

### Bedrock

| Option | Default | What it does |
|---|---|---|
| Enable Bedrock Breaking | off | Breaks the blocks of the block list in the area selection. |
| Bedrock Backend | Auto | Auto / Built-in: the printer breaks it itself. Or a bedrock mod: Fabric-Bedrock-Miner (LXYan2333), Bedrock Miner (bunnyi116), BlockMiner. |
| Targets at Once | 12 | Blocks broken at the same time (with a bedrock mod: at least 5 blocks apart). 1 = one at a time. |
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
| 1.20.1 | `litematica-printer-autyism-1.3.1+1.20.1.jar` | 17 | 0.16.10 or newer | 0.16.3 or newer | 0.15.4 or newer |
| 1.21.5 | `litematica-printer-autyism-1.3.1+1.21.5.jar` | 21 | 0.17.0 or newer | 0.24.3 or newer | 0.22.5 or newer |
| 1.21.6, 1.21.7, 1.21.8 | `litematica-printer-autyism-1.3.1+1.21.8.jar` | 21 | 0.17.0 or newer | 0.25.7 or newer | 0.23.7 or newer |
| 1.21.9, 1.21.10 | `litematica-printer-autyism-1.3.1+1.21.10.jar` | 21 | 0.17.0 or newer | 0.26.8 or newer | 0.24.9 or newer |
| 1.21.11 | `litematica-printer-autyism-1.3.1+1.21.11.jar` | 21 | 0.17.0 or newer | 0.27.0 or newer | 0.26.0 or newer |
| 26.1, 26.1.1, 26.1.2 | `litematica-printer-autyism-1.3.1+26.1.2.jar` | 25 | 0.19.3 or newer | 0.28.12 or newer | 0.27.14 or newer |
| 26.2 | `litematica-printer-autyism-1.3.1+26.2.jar` | 25 | 0.19.3 or newer | 0.29.6 or newer | 0.28.8 or newer |
| 26.3 | `litematica-printer-autyism-1.3.1+26.3.jar` | 25 | 0.19.3 or newer | 0.30.2 or newer | 0.29.1 or newer |

Fabric API, MaLiLib and Litematica are required, each in the build for your Minecraft version.

Optional:

- Mod Menu: settings button in the mod list.
- Autyism's Litematica Enhancement (ALE): visual block picker for all block lists.
- Tweakeroo: use its block-breaking restriction lists for the printer.
- Advanced Shulkerboxes or QuickShulker (mod id `quickshulker`): shulker box restocking. Install it on your client and on the server. Advanced Shulkerboxes has no 26.3 version yet, and no Fabric version for 1.20.1.
- AxShulkers (server plugin): shulker box restocking on servers that use it.
- Servux on the server: Easy Place protocol in multiplayer. Turn on Litematica's *Entity Data Sync* too: without it Litematica doesn't recognise Servux.
- Optional for bedrock mode (the printer can break bedrock itself): Fabric-Bedrock-Miner (LXYan2333), Bedrock Miner (bunnyi116) or BlockMiner.

**Side:** client only. The server does not need this mod; only the optional integrations above have server parts.

## Compatibility

- **Other printers:** do not install together with another Litematica Printer build (BiliXWhite, water2004, aleksilassila). The game refuses to start next to them (mod ids `litematica-printer` and `litematica_printer`).
- **Sodium and Iris:** no known problems; the printer was tested in a 1.21.11 modpack that uses both.
- **Tweakeroo:** works alongside it. The printer's tool switching is its own and does not need Tweakeroo's.
- **Meteor Client:** printing works with NoGhostBlocks on. Bedrock Miner (bunnyi116) cannot break anything while NoGhostBlocks is on; the printer shows a warning. Turn NoGhostBlocks off or use Fabric-Bedrock-Miner (LXYan2333).
- **Carpet:** Carpet's Easy Place protocol (V2) is only used if you select Version 2 in Litematica's settings, because it needs the server's `accurateBlockPlacement` rule.
- **ALE:** optional; neither mod needs the other.

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

Bedrock mode works in survival only and uses Litematica's area selection, not the schematic. The built-in breaker needs pistons, redstone torches and a pickaxe that breaks a piston in one hit while you stand on the ground; the message above the hotbar says what is missing. See also the Meteor note under Compatibility.

Found a bug? Please open an issue: https://github.com/Autyism/litematica-printer-autyism/issues

## Known limitations

- **Left empty on purpose:** observers that would trigger your machine (with Safe Observer Placement on), rails that vanilla cannot place in the right shape (for example tightly stacked ascending powered rails), and anything that depends on those blocks.
- **Not printed:** entities (item frames, armor stands, paintings, minecarts), lily pads, bubble columns, piston heads, and flowing water or lava (these come from the sources). Waterlogged blocks are placed dry unless *Break Ice for Water* is on (survival only).
- **States set by the game:** redstone power, lit states, leaf distance, sapling growth, daylight detector power and similar values are not reproduced and may show as "wrong state" in the progress display.
- **Fluids with buckets:** the printer has to see a face of a neighbouring block, so some sources wait until you move. A source that gets covered by a layer above before it was placed must be placed by hand.
- **Multiplayer:** the work radius is limited by the server's reach; Auto Raise Reach only works in singleplayer; shulker restocking needs the matching mod or plugin on the server; the Easy Place protocol needs Servux on the server.
- **Many materials in survival:** when you use more block types than fit in the hotbar slots, printing is slower on laggy servers. Items come from the main inventory in batches (as many as those hotbar slots hold), and each batch waits for the server.
- **Bedrock Miner (bunnyi116) and Meteor's NoGhostBlocks** do not work together.
- **Translations:** English and Simplified Chinese are complete. In Traditional Chinese, Classical Chinese and Russian, newer options show Simplified Chinese or English text.
