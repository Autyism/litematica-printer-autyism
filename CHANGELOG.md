# Changelog

## 1.0.0 — 2026-10-04

First public release.

- Standalone Litematica printer for Minecraft 1.21.11 (Fabric), based on the BiliXWhite and water2004 Litematica Printer forks. Settings from those builds are imported on first start.
- Layered printing from the bottom up (on by default), with a message for every finished layer.
- Work radius up to 4096; in singleplayer with cheats, your reach is raised automatically (up to 64).
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

- 适用于 Minecraft 1.21.11（Fabric）的独立投影打印机，基于 BiliXWhite 和 water2004 的 Litematica Printer 分支。第一次启动时会导入这些版本的设置。
- 从下往上的分层打印（默认开启），每完成一层都会提示结果。
- 工作半径最大 4096；允许作弊的单人世界会自动调高触及距离（最多 64）。
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
