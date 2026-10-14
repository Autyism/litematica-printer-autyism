# Changelog

## 1.2.0 — 2026-10-14

- New: **Finish Adjusting First** (on by default). A block that still needs clicks (repeater delay, note pitch, comparator mode, lever, door, candles...) is finished before the next block is placed; each click waits for the server. Note pitch and repeater delay are faster: the printer works out how many clicks are needed from what the server confirmed, clicks once per tick, then checks the result.
- Double slabs: the second slab goes in as soon as the server has confirmed the first one, instead of on a later pass (the rest of the build keeps going meanwhile).
- Easy place protocol: if a server refuses it, the printer goes back to turning the head after a few tries and tells you once. **Tip:** on a server with Servux, turn on Litematica's *Entity Data Sync*. Only then does Litematica recognise Servux and use the protocol, and directional blocks (observers, pistons, dispensers...) go down as fast as plain blocks.
- New: **Progress Counts** (default *Whole Schematic*). The progress bar counts the whole schematic in loaded chunks; *Within Range* is the old behaviour.
- New: **Layer Counts as Done** (default *Whole Layer*). Layered printing moves up only when the whole layer in loaded chunks is printed, and the layer messages follow it; *Within Range* is the old behaviour.
- Lighter on the game: with nothing to do, the printer no longer rescans its range every tick, and the progress bar and the layer check only recount where blocks changed.

More: open "Details" below.

<details>
<summary>Details</summary>

- **Why blocks were adjusted late:** after placing a block the printer scanned on, so the block was only clicked when the next scan came back to it, one click per scan (right after placing it also had to wait for the server and the per-block cooldown). Now it stays on that block: click, wait for the server's answer, click again, then the next block. A block that doesn't change after three confirmed clicks, or needs an item you don't have, is left for later as before.
- **Whole layer:** when your range doesn't cover the whole layer, the printer prints what it can reach and waits; walk over to the rest and it carries on. Blocks it couldn't place for a few seconds (for example, missing materials) don't hold the layer. Unloaded chunks can't be checked and don't count.
- **Whole schematic progress:** every block of all enabled placements in loaded chunks; Litematica's render layers apply.
- Tested with simulated lag and with NoGhostBlocks: no block was placed while another one was still being adjusted.

</details>

### 中文

- 新增 **调整完再放下一个**（默认开）：还要点几下的方块（中继器延迟、音符盒音高、比较器模式、拉杆、门、蜡烛……）先调好再放下一个方块，每点一下都等服务器确认。音符盒音高和中继器延迟更快：按服务器确认过的状态算出要点几下，每 tick 点一下，点完再核对。
- 双层台阶：第一块被服务器确认后马上放第二块，不用等下一轮扫描（等的时候别的方块照常放）。
- 轻松放置协议：服务器不接受时，试几次就改回转头放置，并提示一次。**提示：** 服务器装了 Servux 的话，打开投影的“实体数据同步”（Entity Data Sync）。只有打开它投影才认得出 Servux、用协议放，有朝向的方块（侦测器、活塞、发射器……）就能和普通方块一样快。
- 新增 **进度条统计范围**（默认“整个投影”）：进度条统计已加载区块里的整个投影；“工作范围内”是以前的算法。
- 新增 **分层完成判定**（默认“整层”）：已加载区块里的整层都打完才往上一层，每层完成的提示也按这个；“工作范围内”是以前的做法。
- 更省性能：没事可做时不再每 tick 重扫工作范围；进度条和分层判定只重算方块有变化的地方。

更多：点开下面的“详细说明”。

<details>
<summary>详细说明</summary>

- **以前为什么调得晚：** 放下方块后打印机接着往后扫，要等下一轮扫回来才点一下，每轮只点一下（刚放下时还要等服务器确认和这一格的冷却）。现在会停在这个方块上：点一下、等服务器回应、再点，调好了才放下一个。点了三次都没变化、或者缺需要的物品的方块，照旧留到以后再处理。
- **整层：** 工作范围盖不住整层时，打印机先打能够到的部分然后等着，你走过去它就接着打。几秒都放不了的方块（例如缺材料）不会卡住这一层。没加载的区块看不到，不算。
- **整个投影的进度：** 统计所有启用的投影放置在已加载区块里的方块，受 Litematica 渲染层限制。
- 在模拟卡顿和开着 NoGhostBlocks 的情况下测试过：一个方块还没调好时，不会放别的方块。

</details>

## 1.1.1 — 2026-10-08

- Fixed: with cheats off, Auto Raise Reach no longer sends a command, so no "Unknown or incomplete command" in chat.

### 中文

- 修复：没开作弊时，自动调高交互距离不再发指令，聊天栏不会再出现“未知或不完整的命令”。

## 1.1.0+1.20.1 — 2026-10-08

- The printer for Minecraft 1.20.1, with the same features and settings as 1.1.0.
- Needs Java 17, Fabric Loader 0.16.10, MaLiLib 0.16.3 and Litematica 0.15.4 or newer.

### 中文

- 适用于 Minecraft 1.20.1 的打印机，功能和设置与 1.1.0 相同。
- 需要 Java 17、Fabric Loader 0.16.10、MaLiLib 0.16.3 和 Litematica 0.15.4 或更高版本。

## 1.1.0 — 2026-10-07

- New: **Print Waterlogged Blocks** (off by default). Pours water into waterlogged blocks, only where it can't leak out.
- New: **Hide Layer Messages** (off by default). No chat message after each layer.
- Fixed: the missing-material list no longer flickers.

More: open "Details" below.

<details>
<summary>Details</summary>

- **Print Waterlogged Blocks:** the block must already match the schematic apart from the water. Below and the four sides are checked with the game's own water-flow rules. If a neighbour still has to be printed, the printer waits for it. A side the schematic itself leaves open keeps the block dry, and it still counts as finished. Water buckets come from your inventory or shulker boxes; missing ones show up in the missing-material list. The printer waits for the server to confirm the water and tries again if it didn't take. Tested with 45 kinds of waterloggable blocks, also with lag.
- **Missing-material list:** it used to empty for one tick every time the printer rescanned. Now it only updates after a full scan.

</details>

### 中文

- 新增 **打印含水方块**（默认关）：给含水方块倒水，只在水漏不出去的地方倒。
- 新增 **隐藏分层完成提示**（默认关）：每层完成时不发提示。
- 修复：缺失材料提示不再闪烁。

更多：点开下面的“详细说明”。

<details>
<summary>详细说明</summary>

- **打印含水方块：** 世界里的方块除了水以外要已经和投影一样。按游戏自己的水流规则检查下方和四个侧面；旁边还有方块没打印的，会等它打好再倒。投影本身在某一侧就是开着的，这个方块保持不含水，也算完成。水桶从背包或潜影盒里拿，缺的会显示在缺失材料提示里。打印机会等服务器确认已经含水，没加上会重试。用 45 种可含水方块测试过，也测了模拟卡顿的情况。
- **缺失材料提示：** 以前打印机每次重新扫描都会让列表空一下，现在等整轮扫描完才更新。

</details>

## 1.0.0+1.21.10 — 2026-10-07

The printer for Minecraft 1.21.9–1.21.10, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 21, Fabric Loader 0.17.0 or newer, MaLiLib 0.26.8 or newer and Litematica 0.24.9 or newer.
- Other Minecraft versions have their own jar; see the table in the README.

### 中文

适用于 Minecraft 1.21.9–1.21.10 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 21、Fabric Loader 0.17.0 或更高、MaLiLib 0.26.8 或更高、Litematica 0.24.9 或更高。
- 其他 Minecraft 版本有各自的 jar，见说明里的表格。

## 1.0.0+1.21.8 — 2026-10-07

The printer for Minecraft 1.21.6–1.21.8, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 21, Fabric Loader 0.17.0 or newer, MaLiLib 0.25.7 or newer and Litematica 0.23.7 or newer.
- Other Minecraft versions have their own jar; see the table in the README.

### 中文

适用于 Minecraft 1.21.6–1.21.8 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 21、Fabric Loader 0.17.0 或更高、MaLiLib 0.25.7 或更高、Litematica 0.23.7 或更高。
- 其他 Minecraft 版本有各自的 jar，见说明里的表格。

## 1.0.0+1.21.5 — 2026-10-07

The printer for Minecraft 1.21.5, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 21, Fabric Loader 0.17.0 or newer, MaLiLib 0.24.3 or newer and Litematica 0.22.5 or newer.
- Other Minecraft versions have their own jar; see the table in the README.

### 中文

适用于 Minecraft 1.21.5 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 21、Fabric Loader 0.17.0 或更高、MaLiLib 0.24.3 或更高、Litematica 0.22.5 或更高。
- 其他 Minecraft 版本有各自的 jar，见说明里的表格。

## 1.0.0+26.3 — 2026-10-07

The printer for Minecraft 26.3, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 25, Fabric Loader 0.19.3 or newer, MaLiLib 0.30.2 or newer and Litematica 0.29.1 or newer.
- Advanced Shulkerboxes has no 26.3 version yet, so restocking from shulker boxes through it could not be tested on 26.3.
- Other Minecraft versions have their own jar: `litematica-printer-autyism-1.0.0.jar` for 1.21.11, `litematica-printer-autyism-1.0.0+26.1.2.jar` for 26.1–26.1.2.

### 中文

适用于 Minecraft 26.3 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 25、Fabric Loader 0.19.3 或更高、MaLiLib 0.30.2 或更高、Litematica 0.29.1 或更高。
- Advanced Shulkerboxes 目前还没有 26.3 版，所以 26.3 上没法测试通过它从潜影盒补货。
- 其他 Minecraft 版本有各自的 jar：1.21.11 用 `litematica-printer-autyism-1.0.0.jar`，26.1–26.1.2 用 `litematica-printer-autyism-1.0.0+26.1.2.jar`。

## 1.0.0+26.2 — 2026-10-07

The printer for Minecraft 26.2, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 25, Fabric Loader 0.19.3 or newer, MaLiLib 0.29.6 or newer and Litematica 0.28.8 or newer.
- Other Minecraft versions have their own jar: `litematica-printer-autyism-1.0.0.jar` for 1.21.11, `litematica-printer-autyism-1.0.0+26.1.2.jar` for 26.1–26.1.2.

### 中文

适用于 Minecraft 26.2 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 25、Fabric Loader 0.19.3 或更高、MaLiLib 0.29.6 或更高、Litematica 0.28.8 或更高。
- 其他 Minecraft 版本有各自的 jar：1.21.11 用 `litematica-printer-autyism-1.0.0.jar`，26.1–26.1.2 用 `litematica-printer-autyism-1.0.0+26.1.2.jar`。

## 1.0.0+26.1.2 — 2026-10-07

The printer for Minecraft 26.1, 26.1.1 and 26.1.2, with the same features and settings as 1.0.0 for 1.21.11.

- Needs Java 25, Fabric Loader 0.19.3 or newer, MaLiLib 0.28.12 or newer and Litematica 0.27.14 or newer.
- 1.21.11 players keep using `litematica-printer-autyism-1.0.0.jar`.

### 中文

适用于 Minecraft 26.1、26.1.1 和 26.1.2 的打印机，功能和设置与 1.21.11 的 1.0.0 相同。

- 需要 Java 25、Fabric Loader 0.19.3 或更高、MaLiLib 0.28.12 或更高、Litematica 0.27.14 或更高。
- 1.21.11 玩家继续用 `litematica-printer-autyism-1.0.0.jar`。

## 1.0.0 — 2026-10-04

First public release.

- Standalone Litematica printer for Minecraft 1.21.11 (Fabric), based on the BiliXWhite and water2004 Litematica Printer forks. Settings from those builds are imported on first start, including the cycle hotkey and the mode picked in single mode.
- One switch for the printer: **Printer On/Off**. Turning it on while no mode is enabled starts Printing, so it never sits there switched on and doing nothing.
- Each mode's own hotkey starts that mode even while the printer is off. While the printer runs, the hotkey adds or removes that mode, and turning off the last mode turns the printer off.
- **Cycle Mode** steps through the modes. With **Cycle Mode Turns Printer Off** (on by default) it also turns the printer off, so pressing the key by accident can't start mining your build. Bedrock is skipped when no bedrock-breaking mod is installed.
- Layered printing from the bottom up (on by default), with a message for every finished layer.
- Work radius up to 4096. In singleplayer the printer stays within your reach, and with cheats on your reach is raised automatically (up to 64).
- The printer pauses while you use containers.
- Correct orientation for stairs, trapdoors, doors (hinge side and double doors), levers, buttons, vines and more; direction-sensitive blocks wait until the server has registered the head turn.
- Safe rail placement: rails are only placed when they end up in the right shape, otherwise left empty. Observers that would trigger a machine are skipped.
- Note blocks, repeaters, comparators, doors, trapdoors, fence gates and levers are adjusted one confirmed click at a time.
- Shulker box restocking with Advanced Shulkerboxes, QuickShulker or AxShulkers; shulker boxes are only printed with matching contents.
- Built-in tool switching with durability protection, and instant-first mining.
- Bedrock mode for Fabric-Bedrock-Miner (LXYan2333), Bedrock Miner (bunnyi116) and BlockMiner, with an editable block list.
- Optional printing of water, lava and filled cauldrons with buckets (off by default).
- Reliable on laggy servers: waits for server confirmation where it matters, lag pause (also in singleplayer), Easy Place protocol when available, hotbar slot re-sync.
- Filling, fluid removal, mining and block highlighting from the upstream printer.

### 中文

首个公开版本。

- 适用于 Minecraft 1.21.11（Fabric）的独立投影打印机，基于 BiliXWhite 和 water2004 的 Litematica Printer 分支。第一次启动时会导入这些版本的设置，包括轮换快捷键和单模式里选中的模式。
- 打印机只有一个开关：**打印机开关**。一个模式都没开时打开它，会自动开始打印，不会出现开着却什么都不做的情况。
- 各模式自己的快捷键在打印机关着时也能直接开始这个模式。打印机开着时，快捷键用来开、关这个模式；最后一个模式关掉时，打印机也一起关掉。
- **轮换模式**依次切换各个模式。“轮换模式时关闭打印机”默认开启，轮换时会顺便关掉打印机，误按也不会突然开始挖你的建筑。没装破基岩模组时，轮换会跳过破基岩。
- 从下往上的分层打印（默认开启），每完成一层都会提示结果。
- 工作半径最大 4096。单人世界里打印机不会超出你的交互距离；允许作弊时会自动调高交互距离（最多 64）。
- 使用容器时，打印机自动暂停。
- 楼梯、活板门、门（门轴和双开门）、拉杆、按钮、藤蔓等朝向正确；对朝向敏感的方块会等服务器确认转头后再放。
- 铁轨安全放置：只有能放成正确形状时才放，否则留空。会误触发机器的侦测器会被跳过。
- 音符盒、中继器、比较器、门、活板门、栅栏门和拉杆每次只点一下，等服务器确认后再点下一下。
- 通过 Advanced Shulkerboxes、QuickShulker 或 AxShulkers 从潜影盒补货；投影里的潜影盒只用内容物一致的潜影盒来放。
- 内置自动换工具和工具耐久保护，挖掘支持秒破优先。
- 破基岩模式支持 Fabric-Bedrock-Miner（LXYan2333）、Bedrock Miner（bunnyi116）和 BlockMiner，方块列表可编辑。
- 可选用桶打印水、岩浆和装满的炼药锅（默认关闭）。
- 在延迟高的服务器上更可靠：关键操作等服务器确认、延迟过大暂停（单人世界也有效）、能用时使用轻松放置协议、重新同步快捷栏格子。
- 保留上游打印机的填充、排流体、挖掘和方块高亮功能。
