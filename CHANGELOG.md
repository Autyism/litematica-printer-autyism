# Changelog

## 1.2.2 — 2026-11-05

- Fix: in Litematica's main menu (M), buttons that other mods add to the right column (for example Litematica Container Filler's configs and QuickCraft's schematic folder button) could land on the same spot as another button and could not be clicked. A button that overlaps another one now moves down to the next free spot.

### 中文

- 修复：投影主菜单（M）右边一列里，别的模组加的按钮（比如 Litematica Container Filler 的配置、QuickCraft 的打开投影文件夹）可能和另一个按钮叠在同一个位置，点不到。现在叠在一起的按钮会自动挪到下面的空位。

## 1.2.1 — 2026-10-25

- Bedrock mode breaks bedrock itself now, no bedrock mod needed (**Bedrock Backend**: *Auto* / *Built-in*; the bedrock mods can still be picked). It works with pistons and redstone torches like the mods, and a slime block under the torch where nothing else can hold it. **Targets at Once** (default 12) blocks go at the same time, laid out so no torch can power another block's piston. Every step waits for the server, so lag doesn't break it, and pistons, torches and slime blocks are always cleaned up, also when you turn the printer off halfway or walk away. A real bedrock floor goes at about 12–16 blocks per second (it was 1–3). Needs a pickaxe that breaks a piston in one hit (Efficiency III or more) while standing on the ground.
- Layered printing goes back down when a lower layer loses a block (broken, blown up, or in a chunk that wasn't loaded when the layer was checked) and the printer can reach it and has the material. It used to wait until it had come round from the top.
- Fix: with a full inventory and a full shulker box, the printer kept taking shulker boxes into the hand and got nothing out. It now swaps a spare stack into the box for the material; if nothing in the inventory can go into a box, it says so and leaves the boxes alone.
- **Stop When a Placement Moves** is now **Stop When a Placement or Selection Moves**: moving, resizing or replacing the Litematica area selection while the printer is on also turns it off.

## 1.2.0 — 2026-10-22

- New: **Finish Adjusting First** (on by default). A block that still needs clicks (repeater delay, note pitch, comparator mode, lever, door, candles...) is finished before the next block is placed; each click waits for the server. Note pitch and repeater delay are faster: the printer works out how many clicks are needed from what the server confirmed, clicks once per tick, then checks the result.
- With ALE 1.2.0 on the client and the server (or in single player), rails go down directly in the schematic's shape; ramps that vanilla's rules can't place safely are no longer left empty.
- Double slabs: the second slab goes in as soon as the server has confirmed the first one, instead of on a later pass (the rest of the build keeps going meanwhile).
- Easy place protocol: if a server refuses it, the printer goes back to turning the head after a few tries and tells you once. **Tip:** on a server with Servux, turn on Litematica's *Entity Data Sync*. Only then does Litematica recognise Servux and use the protocol, and directional blocks (observers, pistons, dispensers...) go down as fast as plain blocks.
- New: **Progress Counts** (default *Whole Schematic*). The progress bar counts the whole schematic in loaded chunks; *Within Range* is the old behaviour.
- New: **Layer Counts as Done** (default *Whole Layer*). Layered printing moves up only when the whole layer in loaded chunks is printed, and the layer messages follow it; *Within Range* is the old behaviour.
- Lighter on the game: with nothing to do, the printer no longer rescans its range every tick, and the progress bar and the layer check only recount where blocks changed.
- Layered printing gets to the next unfinished layer much sooner. Turning the printer on above many finished layers used to take several seconds to half a minute before anything was placed; finished layers are now checked from the progress bar's counts (kept up to date while the printer is off) and several per tick. A layer still only counts as done when every block in it is correct and the server has confirmed the printer's last placements.
- Faster scanning: blocks that are already correct are no longer worked out for placement on every pass.
- New: **Stop When a Placement Moves** (on by default). Moving, rotating, mirroring or resetting a placement (or one of its sub-regions) turns the printer off at once, so it never places blocks for the old position.
- A client-side haste (Meteor's SpeedMine, for example) no longer counts for mining: blocks are broken at the speed the server accepts instead of flashing back.
- Fix: tool durability protection now counts the breaks the server hasn't confirmed yet. With a haste beacon and same-tick instant mining over a big area, dozens of blocks were broken before the client saw the tool wear, and a tool could break; now mining stops at the threshold.
- With Litefastica (fast schematic moving), the printer waits until a moved placement has really moved in Litematica's data, about 1.5 seconds after the last move.
- Fix: bedrock mode over an area put pistons and torches around many blocks at once and broke none of them. Now the bedrock miner gets one block at a time; the next one goes only after the server has broken it and the miner has cleaned up. Pistons and torches the miner leaves behind (for example when you walk away while it works) are broken by the printer once you are back in reach.
- Tool durability protection also covers bedrock mode: the pickaxe the bedrock miner will use is checked before every block, and while bedrock mode runs, a break with a nearly broken tool in hand is stopped before it reaches the server.
- Fluid removal: new **Sources Only** (on by default). Only source blocks are filled; the flowing liquid then drains away by itself. It replaces *Include Flowing Liquids*.
- With ALE 1.2.0 on the client and the server (or in single player), deepslate is mined over an area like an instant block (*Instant Mining* on, netherite pickaxe with Efficiency V, Haste II). Mining by hand is not affected.
- Much faster in survival. A full stack used to stop at 32, then 16, 8, 4... to wait for the server, so plain blocks went down row by row; now the printer only holds back the placements the server hasn't counted yet.
- Fix: clicking with an empty hand (turning a redstone cross into a dot) waited 10 seconds every time in survival, and the rest of the layer waited with it.
- About twice as many blocks per tick: printed blocks no longer each play a placement sound (one per tick is kept). Dozens of sounds per tick took time on every block.
- Plain blocks go down much faster. Each position is read once per check instead of up to six times, cooldowns are looked up directly by position, nothing is recorded for highlights while highlighting is off, and the lag check no longer holds the first ticks after the printer is turned on. A 24×24 floor in survival on a laggy connection now takes about a third of a second (it was close to a second).
- If another mod turns off the game's own block prediction (Meteor's NoGhostBlocks, for example), the printer notices after a few blocks and sends placements straight to the server, skipping the client-side simulation that had no effect anyway.
- Survival: when a stack runs out, the printer goes on with another stack of the same item in the hotbar at once. It used to count unconfirmed placements per item, so every stack looked possibly used up while many placements were on the way.
- Faster item swaps in survival: each pass first places what is already in the hotbar, then brings everything the rest of the pass needs into the hotbar in one go, instead of one item at a time with a wait for the server after each. Empty slots are used first, then the slot the printer used longest ago, so a block that is used all the time stays in the hotbar (only Litematica's *pickBlockableSlots* are used, as before). A large area of one block gets several stacks at once, and when a stack runs out the printer carries on with the next stack in the hotbar instead of waiting.
- With Servux, repeater delay, note pitch, comparator mode and open doors or trapdoors come out right straight away. Once the first one is confirmed, the printer no longer stops after each of these blocks to wait for the server.
- On a Carpet server with *antiCheatDisabled* (Carpet on the client too), the work range is kept to the 32 blocks the server accepts. Blocks further away used to be tried again and again for nothing.
- Blocks the printer can't place (end portal, piston heads) and blocks with nothing to stand on yet no longer hold up a layer.
- Missing materials: the first time an item runs out, the printer still waits a few seconds at that layer. After that the item no longer holds up every layer, and when nothing else is left the printer rests instead of going round and round. It is placed as soon as you have it.
- When the rest of a layer is out of reach, the HUD says where it is, so the printer no longer looks stuck.
- A shulker box that never opens is given up after 3 seconds instead of holding the printer.
- New: **Observer Before Its Supported Block** (on by default). When the block in front of an observer can only stand on that observer (a repeater or rail on top of it), neither could be placed before. Now the observer goes first, but only if placing it, and its pulse when that block is added, move or power nothing nearby: no piston pushed, no wire or part switched, no BUD piston, dropper, dispenser or crafter set off, no other observer triggered.
- New: **Observer Impact Check** (on by default). Every observer is checked like this before it is placed, and so is a block placed or clicked in front of an observer (that makes it fire). Pistons and droppers powered through quasi-connectivity, doors, hoppers, rails, lamps, repeaters and comparators are judged the way the game does it. If anything would react, it is left out like other unsafe observers.

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
- 客户端和服务端都装了 ALE 1.2.0（或单人游戏）时，铁轨直接放成投影里的形状；原版规则下没法安全放的上坡不再空着。
- 双层台阶：第一块被服务器确认后马上放第二块，不用等下一轮扫描（等的时候别的方块照常放）。
- 轻松放置协议：服务器不接受时，试几次就改回转头放置，并提示一次。**提示：** 服务器装了 Servux 的话，打开投影的“实体数据同步”（Entity Data Sync）。只有打开它投影才认得出 Servux、用协议放，有朝向的方块（侦测器、活塞、发射器……）就能和普通方块一样快。
- 新增 **进度条统计范围**（默认“整个投影”）：进度条统计已加载区块里的整个投影；“工作范围内”是以前的算法。
- 新增 **分层完成判定**（默认“整层”）：已加载区块里的整层都打完才往上一层，每层完成的提示也按这个；“工作范围内”是以前的做法。
- 更省性能：没事可做时不再每 tick 重扫工作范围；进度条和分层判定只重算方块有变化的地方。
- 分层打印更快开始打下一层：以前在很多打完的层上面开打印机，要等几秒到半分钟才开始放；现在打完的层直接用进度条的统计判断（打印机关着时也一直在更新），一 tick 能过好几层。一层还是要每一格都对、并且打印机放的方块都被服务器确认了才算完成。
- 扫描更快：已经对了的方块不再每一轮都去算怎么放。
- 新增 **投影移动时关闭打印机**（默认开）：投影（或它的子区域）一被移动、旋转、镜像或重置，打印机立刻关掉，不会按旧位置放方块。
- 客户端自己加的急迫（比如 Meteor 的 SpeedMine）不再算进挖掘速度：按服务器认的速度挖，方块不会再闪一下又回来。
- 修复：工具耐久保护把服务器还没确认的挖掘也算进去。开着急迫信标、同一 tick 大范围秒挖时，客户端看到耐久变化之前已经挖了几十格，工具可能被用坏；现在到阈值就停。
- 装了 Litefastica（快速移动投影）时，打印机等投影数据真正挪到新位置（停手后约 1.5 秒）再继续。
- 修复：破基岩模式框一片范围时，会在很多格旁边同时放活塞和火把，结果哪一格都破不掉。现在一次只交给破基岩模组一格，服务器确认破掉、破基岩模组收拾完以后才交下一格。破基岩模组留下的活塞和火把（比如它干到一半你走开了），你回来以后打印机会拆掉。
- 工具耐久保护也管破基岩模式：每交一格之前检查破基岩模组要用的镐；破基岩模式开着时，手上的工具快坏了，挖掘直接拦下，不发给服务器。
- 排流体新增 **只填流体源头**（默认开）：只往源头里填方块，流动的液体随后自己消失。取代原来的“包含流动液体”。
- 客户端和服务端都装了 ALE 1.2.0（或单人游戏）时，深板岩可以像能秒破的方块一样大范围秒挖（要开“即时挖掘”，下界合金镐效率 V、急迫 II）。自己手动挖不受影响。
- 生存模式快很多：以前一组方块放到 32 个就停下来等服务器，然后 16、8、4……普通方块也是一排一排地放；现在只等服务器还没算进数量里的那几次。
- 修复：生存模式空手点（把十字红石点成点）每次都要卡 10 秒，这一层后面的方块都跟着等。
- 每 tick 能放的方块多了约一倍：打印的方块不再每块都播一次放置声（每 tick 留一次）。以前一 tick 几十个声音，每一块都要花时间。
- 普通方块放得快很多：同一格每次判断只读一遍方块（以前最多读六遍），冷却直接按坐标查，高亮关着时不再记高亮，刚打开打印机的头几 tick 也不会再被延迟检测拦住。生存模式、网络有延迟时，24×24 的地板大约三分之一秒放完（以前接近一秒）。
- 别的模组关掉了游戏自己的方块预测时（例如 Meteor 的 NoGhostBlocks），打印机放几块就能发现，之后直接把放置发给服务器，省掉客户端那一遍本来就不起作用的模拟。
- 生存：一组用完时，马上接着用快捷栏里同样物品的另一组。以前按物品统计还没确认的放置，放置一多，每一组都像是可能已经用完了。
- 生存模式换物品快了：每一轮先放快捷栏里有的，再把这一轮剩下要用的物品一次换进快捷栏，不再一次换一种、每换一种等一次服务器。先用空格子，再换掉打印机最久没用的那一格，一直在用的方块会留在快捷栏里（和以前一样只用 Litematica 的“可选取方块的物品栏位”）。一大片同一种方块时一次换进好几组；一组用完直接接着用快捷栏里的下一组，不用等服务器。
- 有 Servux 的服务器上，中继器延迟、音符盒音高、比较器模式、开着的门和活板门一放下就是对的；确认过一次以后，不再每放一个都停下来等服务器。
- 开着 *antiCheatDisabled* 的地毯服务器（客户端也装了地毯）：工作范围按服务器认的 32 格算。以前更远的方块一直在白放。
- 放不了的方块（末地传送门、活塞头）、还没有东西可以贴的方块，不再挡住一层。
- 缺材料：某种材料第一次用完时，还是在这一层等几秒；之后它就不再挡每一层，别的都打完了打印机会歇着，不再一圈一圈地转。材料一有就照常放。
- 一层剩下的部分够不着时，HUD 会显示在哪里，不再看起来像卡住了。
- 打开潜影盒一直打不开时，3 秒后放弃这一次，不再让打印机一直等。
- 新增 **侦测器先于依附它的方块**（默认开）：侦测器面前那一格的方块要靠这个侦测器才放得下（例如它上面的中继器、铁轨），以前两边都放不了。现在先放侦测器，但前提是放下侦测器、以及那个方块放上去时它亮的那一下，都不会动到周围：不推活塞、不让线路或元件变化、不触发 BUD 状态的活塞 / 投掷器 / 发射器 / 合成器、不触发别的侦测器。
- 新增 **侦测器放置影响检测**（默认开）：每个侦测器放之前都这样检查一遍；在侦测器面前放方块、点方块（会让它亮一下）之前也检查。准连接的活塞和投掷器、门、漏斗、铁轨、灯、中继器、比较器都按游戏本身的规则判断。周围有东西会被带动就先不放，和其他不安全的侦测器一样跳过。

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
