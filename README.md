# MaidSync

**修复车万女仆（Touhou Little Maid）在 Minecraft 1.21.1 上「女仆被远距离传送后客户端看不见她」的问题。**

服务端一切正常——她照常打怪、`/tp` 找得到、仆人铃能找到她；
但客户端**看不见模型、看不见碰撞箱**（F3+B 也没有），大地图上她停在**传送前的老位置**并且一直闪。

---

## 症状

| | |
|---|---|
| 触发 | 女仆被远距离传送之后（仆人铃 / 排位表传送 / 任何把她拉回主人身边的传送） |
| 服务端 | **完全正常**——照常工作、能 `/tp`、能打怪 |
| 客户端 | 模型和碰撞箱一起消失；大地图把她画在原位置且闪烁 |
| 临时解法 | 重进存档恢复；`/maid_smart resync`（promaid）也能当场恢复 |

**注意：卸掉所有其他模组、只留车万女仆，问题依然复现。** 这跟哪个模组冲突无关。

---

## 根因

实测定位，完整收包序列：

```
15:02:45.749  ← 移除包 ids=[16]                          ← 玩家被传走，批量移除（含她）
15:02:45.791  ← 生成包 女仆#16 @ -428.2/-60.0/-577.5     ← ★ 42ms 后加回，坐标是【旧位置】
15:02:45.925  ← 传送包 女仆#16 目标 -100.5/-60.0/156.5   ← 然后才把她传向玩家
```

链条：

1. 客户端在**一个尚未加载的区块**里创建了那个实体
2. 该 section 状态是 `TRACKED` 而不是 `TICKING` → 实体进不了 `ClientLevel.tickingEntities`
3. `Entity.tick()` 永不执行（实测 `tickCount` 冻在 3，三十秒不动）
4. `LivingEntity` 的插值走不完 —— `lerpTo(..., steps=3)` 只设置 `lerpSteps=3`，
   每 tick 执行 `d0 = getX() + (lerpX - getX()) / steps`，**第一步恰好落在起终点的 1/3**，
   不 tick 就永远停在 1/3
5. 该 section 未编译 → `LevelRenderer` 渲染循环第 2 道门 `isSectionCompiled` 跳过她
   → **模型和碰撞箱一起消失**
6. 服务端 `positionCodec` 基线已同步 → `delta ≈ 0` → 认为客户端知道她在哪
   → **再也不发位置包** → 永不自愈

### 为什么只有 1.21.1 有

1.21 给 `ChunkMap.TrackedEntity.updatePlayer` **新增了第三道判定门**：

```java
// 1.20.1 —— 只有两条
boolean shouldSee = distSq <= rangeSq
                 && entity.broadcastToPlayer(player);

// 1.21.1 —— 多了一条
boolean shouldSee = distSq <= rangeSq
                 && entity.broadcastToPlayer(player)
                 && chunkMap.isChunkTracked(player, cx, cz);
                 //   = player.getChunkTrackingView().contains(cx, cz)
                 //     && !player.connection.chunkSender.isPending(pos)
```

支撑它的 `ChunkTrackingView` 和 `PlayerChunkSender` **在 1.20.1 里根本不存在**（1.21 才加的）。

`isPending` 会在"区块正在发送/卸载"的中间态翻转，让 `shouldSee` 在**实体没动的情况下**
false → true —— 于是出现「移除 → 旧位置重新加回 → 才传送」的 42 毫秒错位。
1.20.1 的判定里两个量都不会瞬变，所以从来没有这个序列。

---

## 本模组做什么

检测到位置大跳变后，**延迟约 1 秒**补一次「删 + 生成 + 实体数据 + 装备」，
让客户端在**玩家身边那个已加载的区块**里重建实体。

**为什么必须延迟**：当帧补包带的是旧坐标（生成包比传送包早 134ms），
那一刻她的服务端位置可能还没更新，补了也没用。等传送真正生效后再补，
生成包才会带玩家身边的新坐标。

实现上挂在 `ServerEntity.sendChanges()` —— 全原版**唯一**的位置包出口，
所以无论女仆被谁、用什么方法传走都会经过，不需要枚举各种传送入口。

### 实测有效性

| | 修复前 | 修复后 |
|---|---|---|
| 客户端 `tick列表=不在`（冻结态） | **229 次采样，持续 30 秒，从未恢复** | **11 次，全部 1 秒内自愈** |
| 玩家感受 | 永久看不见 | 察觉不到 |

一次完整的恢复：

```
15:18:16.052  帧探针 距=1320.0  tick列表=不在  tick=7
15:18:16.671  [maidsync] 延迟补包：已给 1 个客户端重建 斯塔·柏(maid#29011)
15:18:16.725  帧探针 距=3.9     tick列表=在    tick=1    ← 50ms 后已在玩家身边
```

### ⚠️ 它**不**做什么

- **不阻止冻结发生**，只把它从"永久"变成"约 1 秒内自愈"。每次远距离传送仍会短暂冻一下
- **不根治**。根治要改车万女仆的传送链路（传送前确认客户端已有目标区块），或改原版
- **对 1.20.1 没有意义**（那个版本没有这个问题）

---

## 安装

1. 需要 **Minecraft 1.21.1 + NeoForge 21.1.228+** 和 **车万女仆 1.5.0+**
2. 把 jar 放进 `mods/`
3. 配置文件在 `config/maidsync-server.toml`

## 配置

| 键 | 默认 | 作用 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `deferredRebuildDelayTicks` | `20` | **延迟补包的延迟（tick）**。太短会被传送后紧随的同步冲掉；太长玩家要多等一会儿 |
| `rebuildDistance` | `64.0` | 多大的位置跳变算"远距离传送" |
| `affectAllEntities` | `false` | 对所有实体生效（默认只对女仆） |
| `extraEntityTypes` | `[]` | 额外要管的实体注册名 |
| `debugLog` | `true` | 打印重建详情 |
| `diagnose` | `false` | 诊断探针（排查时才开，日志会变大） |

修复生效时日志里会有：

```
[maidsync] 延迟补包：已给 1 个客户端重建 斯塔·柏(maid#29011)（删+生成+数据+装备）
```

---

## 从源码构建

本模组**不用 Gradle**，直接 `javac` + `jar`（因为要对着整合包里现成的库编译）。

```bash
export MAIDSYNC_LIB="/path/to/.minecraft/libraries"
export MAIDSYNC_MODS="/path/to/.minecraft/versions/<实例名>/mods"
export MAIDSYNC_JDK="/path/to/jdk-21/bin"     # 必须 JDK 21
bash build.sh
```

也可以直接改 `build.sh` 顶部三行。脚本会把编好的 jar 复制到 `MAIDSYNC_MODS`。

> ⚠️ **必须 JDK 21**：更高版本编出的 class 文件版本在 1.21.1 上跑不了。
> 脚本里加了 `-proc:none`——sponge-mixin 的注解处理器会被 javac 自动发现，
> 但它需要 ASM 才跑得起来，而它唯一的产出 refmap 在 NeoForge 1.20.2+ 上并不需要。

---

## 诊断探针

把 `diagnose` 设为 `true`，会额外输出：

| 标记 | 内容 |
|---|---|
| `[maidsync/探针]` | 每 2 秒：`ServerEntity.sendChanges()` 调用次数、实体在不在 `ChunkMap.entityMap`、位置基线 vs 真实位置 |
| `[maidsync/帧探针]` | 帧内比对「在渲染列表里但这一帧没渲染」+ **实体在不在 `ClientLevel.tickingEntities`** + 插值状态 |
| `[maidsync/客户端]` | 客户端收到的每一个与该女仆相关的包及其坐标 |

这三样是把这个问题定位下来的关键工具。

---

## 致谢

感谢 **promaid** 的作者 —— 它的 `/maid_smart resync` 命令（同样的"删+生成"四包序列）
是最早被验证有效的解药，本模组的做法就是把它自动化。

`ServerEntity.sendChanges()` 是唯一的位置包出口、以及 1.21 新增 `isChunkTracked` 门这两条结论，
来自对原版字节码的直接比对；完整排查过程（含走过的弯路）见随附的分析记录。

## 许可

MIT
