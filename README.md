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
3. 配置文件是 `config/maidsync-server.toml`（`ModConfig.Type.SERVER`）。
   部分环境会在**存档目录下**另建一份 `saves/<存档>/serverconfig/maidsync-server.toml` ——
   **两份都存在时以存档里那份为准**，改完没生效就先去看那一份在不在。

### 装在服务端就够了

修复**全部在服务端完成**（改的是 `ServerEntity.sendChanges` 和补包时序），
所以**只装在专用服务端、客户端不装也能用** —— 本模组不注册任何网络通道，
客户端那边全是诊断探针，不装只是看不到客户端的探针日志。

反过来，如果你在自己客户端也装了，不会有副作用。

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
| `skipSubLevels` | `true` | 跳过 Sable 物理子关卡上的实体（见下节） |
| `rebuildCooldownTicks` | `40` | 同一只女仆两次重建的最小间隔（tick，0 = 关闭）。见「重建冷却」一节 |

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
export MAIDSYNC_OUT="/path/to/输出目录"        # 可选，默认 = MAIDSYNC_MODS
bash build.sh
```

也可以直接改 `build.sh` 顶部三行。脚本会把编好的 jar 复制到 `MAIDSYNC_OUT`（默认 `MAIDSYNC_MODS`）；
只想产出 jar、不想动整合包时把 `MAIDSYNC_OUT` 指到别处。

车万女仆的 jar 按 `touhoulittlemaid-*.jar` 自动匹配（不同整合包里版本号不一样）。

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

## Sable 物理子关卡上的女仆会持续抖动（2.1.2 修）

**症状**：女仆站在 Sable 的子关卡（飞船/载具）上时，一直抖动抽搐。

**根因**：Sable 的判定是

```java
// dev.ryanhcode.sable.api.entity.EntitySubLevelUtil
public static boolean shouldKick(Entity entity) {
    return !entity.getType().is(SableTags.RETAIN_IN_SUB_LEVEL);
}
```

而 `data/sable/tags/entity_type/retain_in_sub_level.json` 里只有
`#sable:create_contraption`、`#sable:super_glue`、`#sable:wall_entities`、各种座位/展示实体/
矿车/盔甲架/雪傀儡/拴绳结 —— **`touhou_little_maid:maid` 不在里面**。

于是女仆站在子关卡上时，Sable 会在**每 tick 的碰撞解算**
（`SubLevelEntityCollision.collide`）里把她从 plot 坐标系**踢**回世界坐标系：

```java
// EntitySubLevelUtil.kickEntity
entity.moveTo(sub.logicalPose().transformPosition(pos.add(offset)).subtract(offset));
entity.setDeltaMovement(pose.transformNormal(entity.getDeltaMovement()).add(velocity));
```

那是一次 `Entity.moveTo(Vec3)`，而它的重载链是

```
moveTo(Vec3) → moveTo(DDD) → moveTo(DDDFF)
                                 ↑ 本模组的传送嗅探点就钩在这里
```

位移量级是 plot 坐标系与世界坐标系的间距（**几百到上千格**，远超 `rebuildDistance`
默认的 64）—— 于是**每 tick 都判定为「远距离传送」，每 tick `removePairing`+`addPairing`
删一次客户端实体再生成**。

更糟的是补包发的是裸 `ClientboundAddEntityPacket`（带**世界坐标**），而 Sable 客户端侧的
`sable$recreateFromPacket` 会（同样因为 `shouldKick` 为真）把那份世界坐标当成 plot 坐标
**再变换一次**，把她摆到另一个错位置 —— 闭环成立。

**修法**：实体在子关卡里、或正追踪着子关卡时，不参与本模组的标记与重建。
`ServerEntityMixin` 里这道门**刻意放在 `MaidSyncPending.consume` 之前** ——
标记是"她被真正传送过"的凭证，在子关卡上的位置变化不是传送，消费掉就等于白白丢弃；
留着它，等她真正离开子关卡后的第一次 `sendChanges` 再补一次重建。

判定在 `com.maidsync.compat.SableGate`，**全程反射、不引用任何 Sable 类型**：
这个类第一次被服务端 tick 路径加载，而没装 Sable 的专用服务器必须在加载它时毫发无伤。
（`SableCompat` 做不到这点 —— 它有一个以 `SubLevel` 为参数类型的私有方法，类校验时就要解析。）

### ⚠️ 「把她加进 `sable:retain_in_sub_level` 标签」这条路**不要走**

曾经以为把 `touhou_little_maid:maid` 加进那个标签是更根本的解法（Sable 就不踢她了）。
**实测证明它得不偿失，已放弃。**

原因是它会把女仆的**服务端坐标变成 plot 坐标（几千万格量级）**，而主人、她的"家"、
其他实体全都还在正常世界坐标里。于是**任何拿她跟世界里某点算距离或包围盒的模组都会炸**：

| 实测撞上的模组 | 怎么撞的 | 后果 |
|---|---|---|
| **MaidUseHandCrank** | `findCrankHandle` 取"搜索中心 ∪ 她"的外接矩形，逐区块查 POI | 矩形宽 2048 万格 → 每个区块**同步读盘** → **服务器单 tick 卡死 40 秒+** |
| **promaid** | `trySameDimPull` 算 `maid.distanceToSqr(owner)` | 判定她"离主人几千万格" → 反复把她传送出结构 |

而且**它并不解决原来那个问题** —— 「结构移动、女仆留在原地」的真正原因是客户端实体被反复
重建（见下一节），服务端她一直跟着走得好好的。撤掉标签之后，实测**站着也照样跟着飞船走**。

**别给自己加这个标签。** 本模组的 `skipSubLevels` 已经足够。

---

## 延迟补包会把女仆从载具/坐垫上摘下来（2.1.3 修）

**症状**：女仆坐在坐垫/椅子上时——**不播放下坐动画**，而且**位置错位**。
用 Carry On 把她放到机械动力的坐垫上、或者 `Shift`+右键让她自己坐下，都会这样。

**根因**：`MaidSyncDeferred.sendResync` 的四包序列**少了乘客包**。

```
① ClientboundRemoveEntitiesPacket    ← 客户端 Entity.setRemoved(DISCARDED)
                                         └─ if (shouldDestroy()) stopRiding()   ← 从载具上摘下来
② ClientboundAddEntityPacket         ← 裸生成包，包里【没有载具字段】→ 以"没骑东西"重建
③ ClientboundSetEntityDataPacket
④ ClientboundSetEquipmentPacket
```

服务端并不会自己纠正：乘客名单是**载具那一侧**的 `ServerEntity` 在"名单变了"时广播的
（`ServerEntity.sendChanges` 开头那一段），而服务端眼里名单从来没变过 —— 变的是客户端。
于是那一包永远不发第二次，**脱钩是永久的**。

两个症状都从这里来：

| 症状 | 机制 |
|---|---|
| **不播放下坐动画** | TLM 的坐下动画是 `AnimationRegister` 里的 `"chair"`，触发条件就是 `maid.asEntity().isPassenger()`。客户端已不是乘客 → 永不播放 |
| **位置错位** | 她不再套用载具的乘客挂点（`getPassengerAttachmentPoint`）；更要命的是 `ServerEntity.sendChanges` 对乘客**只发转向、不发坐标**（位置本该由载具带），于是她**永久卡在生成包给的那个坐标**上 |

**修法**：照抄原版 `ServerEntity.sendPairingData` 的结尾，补发两包 ——
自己载的乘客（`SetPassengersPacket(maid)`）与**自己所乘载具**
（`SetPassengersPacket(maid.getVehicle())`），连条件与顺序都对齐。

日志里也加了标记，下次实测可以直接确认：补包那一行末尾出现 `+载具` 就说明当时她在骑东西、
并且这次补包带了载具包。

> ⚠️ 只影响**延迟补包**这条路径。当帧的 `removePairing`+`addPairing` 走的是原版
> `sendPairingData`，它本来就带乘客包（`if (this.entity.isPassenger())`），没有问题。

---

## `skipSubLevels` 的门（2.1.2 加，2.1.5 起实测正常）

`SableGate` 的反射降级会打日志，方便确认这道门到底有没有在工作：

- 成功 → `[maidsync] Sable 子关卡门已就绪（getContaining + sable$getTrackingSubLevel）`
- 失败 → `[maidsync] Sable 子关卡门反射失败 —— skipSubLevels 将一直不生效：...`

（打开 `diagnose` 还能看到每次跳过：`[maidsync] 子关卡跳过（sendChanges）：...`）

### 一段弯路：这道门一度看起来"没生效"

2026-09-21 的实测里出现过 28.9M 格的"跳变"照样触发重建：

```
[maidsync] 跳变 28964516.5 格（位置基线判定）→ 已让 1 个客户端重建该实体
```

当时查到的原因是：**女仆被"保留"在子关卡里**（有人给她加了 retain 标签），
于是她的服务端坐标是 **plot 坐标**，而位置基线是世界坐标 —— 两者相减正好是
两套坐标系的距离（实测数字与 `√(Δx²+Δz²)` 对得上）。这不是真的跳变，是拿两套坐标系相减。

**撤掉那个标签之后，同一个场景下 0 次跳变**，门的日志也从"从不命中"变成正常命中
（一次实测里 `sendChanges` 命中 52 次、`moveTo` 命中 8 次）。

> 也就是说：**这道门本身一直是好的**，让它看起来失效的是 retain 标签。
> 这又是一条"别给自己加那个标签"的理由。

`ServerEntityMixin` 里这道门**刻意放在 `MaidSyncPending.consume` 之前** ——
标记是"她被真正传送过"的凭证，在子关卡上的位置变化不是传送，消费掉就等于白白丢弃；
留着它，等她真正离开子关卡后的第一次 `sendChanges` 再补一次重建。

---

## 客户端探针现在也记录乘客包（2.1.4）

`ClientboundSetPassengersPacket` 是**唯一**能建立客户端载具关系的包 ——
而 TLM 的「坐在坐垫/椅子上」动画（`AnimationRegister` 里的 `"chair"`）触发条件就是
`isPassenger()`。**它偏偏是唯一没被记录的包。**

现在记了：

```
[maidsync/客户端] ← 乘客包 载具#123[create:seat] 乘客=[#456(maid)] || 女仆状态：女仆#456 isPassenger=true
```

三种信息都在一行里：

| 看什么 | 说明 |
|---|---|
| `载具#N` 后面的方括号 | 客户端**认不认得**这个载具。写成 `★客户端查无此实体` 就说明生成包还没到 —— 原版这时打一句 `Received passengers for unknown entity` 然后**整包丢掉** |
| `乘客=[...]` 里每一项 | 同理，`★查无` 表示客户端还没有那只实体 |
| 行尾的 `isPassenger=` | 收到包之前她是什么状态。**连着两条对比**就能看出这个包到底有没有让她骑上去 |

与女仆无关的乘客包（矿车、船、别的模组的座位）**不记**，不会刷屏。

---

## 重建冷却（2.1.5 新增）

**配置**：`rebuildCooldownTicks`，默认 **40**（= 2 秒），`0` = 关闭。

**为什么需要**：判定一旦连续成立，就会**每 tick 重建一次**。最典型的就是上面那节说的
Sable 坐标系错配 —— `delta` 恒为几千万格，于是每 tick 一次 `removePairing + addPairing`，
每次还排一套延迟补包（每 20 tick 一次完整的六包重传）。而 promaid 又在做同样的
「删+生成+数据+装备」，两边叠加就更浪费。

**为什么不会把原来那个 bug 放回来**：冷却只挡**重复**动作，不挡第一次 ——
跳过时会把标记**原样还回去**（`MaidSyncPending.mark`），所以该修的那次一定还会修，
只是挪到冷却结束。代价是：真被冻住的女仆从「约 1 秒自愈」变成「最坏 1 秒 + 冷却时长」。
反过来，重建风暴的频率从**每 tick 一次**降到**每冷却时长一次**。

**跳过时刻意不 `ci.cancel()`**：cancel 是有代价的，它会吞掉本 tick 的旋转包与脏数据同步。
只有在我们**确实要重建**时，用重建去换掉那些包才划算；只是跳过的话，让原版照常发包更好。

> 而且在 Sable 那种错配场景里，**原版自己算出来的 `delta` 是对的**（它读的位置和基线
> 在同一个坐标系里），本来就不会发什么有害的包 —— 有害的一直是本模组的误判。

**用 UUID 而不是实体 id 做键**：`MaidSyncPending` 用 id，因为它误判的后果只是"多重建一次"；
冷却反过来，误判的后果是**该修的没修**，而实体 id 会被复用 —— 一个刚冷却完就被回收的 id
可能把冷却扣到一只全新的女仆头上。

日志里能看到它在工作（按 `debugLog` 打，5 秒最多一条）：

```
[maidsync] 冷却中，跳过重建：DS鲸鱼娘(maid#36079) 距基线 28966106.1 格（40 tick 内已重建过一次，标记已保留）
```

---

## 版本

- **2.1.5**
  - 新增**重建冷却**（`rebuildCooldownTicks`，默认 40 tick = 2 秒）。见上面「重建冷却」一节。
  - 修一个**客户端崩溃**：`LevelRendererMixin` 里原来用 `SableCompat.isLoaded()` 当守卫，
    但那个类自己就 import 了 Sable 类型（还有个以 `SubLevel` 为参数类型的方法），
    **类校验时就要解析** —— 没装 Sable 的客户端一走到那里就 `NoClassDefFoundError`，
    它的 `isLoaded()` 根本来不及执行。改为用 `SableGate.modPresent()` 当门
    （那个类一个 Sable 类型都不出现）。同时删掉死代码 `clearStaleTracking` / `isLoaded`。
    *影响面：只在客户端 + 打开 `diagnose` 时才会走到，所以此前没被发现。*
- **2.1.4** —— 客户端探针补上 `ClientboundSetPassengersPacket`（见上一节）。判定逻辑无改动。
- **2.1.3** —— 修「延迟补包把女仆从载具/坐垫上摘下来」（症状：坐下不播放动画 + 位置错位）：
  四包序列末尾补发自己载的乘客包与**自己所乘载具**的乘客包，对齐原版 `sendPairingData`。
  同时给 `SableGate` 的反射降级加了日志。详见上面两节。
- **2.1.2** —— 新增 `skipSubLevels`（默认开）：跳过 Sable 物理子关卡上的实体。
  修「女仆站在飞船/载具上时持续抖动抽搐」——那是 Sable 每 tick 把她从 plot 坐标系
  踢回世界坐标系（一次几百到上千格的 `moveTo`）被本模组误判成远距离传送、
  进而每 tick 重建实体造成的。判定走反射（`SableGate`），没装 Sable 时零开销。
- **2.1.1** —— 补包的**收件人判定**收紧：只发给「服务端追踪表里现在真的有她」的客户端
  （原版 `ChunkMap.TrackedEntity.seenBy`），不再按「同维度 + 非主人 128 格内 / 主人无条件」硬筛。
  原因是补包只能用裸包、服务端不会因此登记追踪关系，给没在追踪她的客户端发会造出**幽灵实体**；
  主人那一档原来的 `Double.MAX_VALUE` 尤其危险 —— 传送若没真正落到主人身边，
  会给他发一份远处未加载区块里的生成包，正好又造出本模组要修的那个冻结态。
  读不到追踪表时（字段改名等）自动退回原版 `updatePlayer` 的同一口径，并留一行日志。
- **2.1.0** —— 延迟补包（删+生成+数据+装备），实测把「永久冻结」变成「约 1 秒内自愈」。

## 致谢

感谢 **promaid** 的作者 —— 它的 `/maid_smart resync` 命令（同样的"删+生成"四包序列）
是最早被验证有效的解药，本模组的做法就是把它自动化。

`ServerEntity.sendChanges()` 是唯一的位置包出口、以及 1.21 新增 `isChunkTracked` 门这两条结论，
来自对原版字节码的直接比对；完整排查过程（含走过的弯路）见随附的分析记录。

## 许可

MIT
