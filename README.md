# Curios Slot

一个轻量 Forge 模组（Minecraft **1.20.1** / Forge **47.x**），允许你在游戏内**通过指令为实体添加或设置 Curios 饰品栏位**。

- 为**玩家**、**车万女仆**等 Curios 集成实体调整饰品栏位；
- 可给**普通生物**（牛、僵尸等）创建原本不存在的饰品栏位（基于 Curios 数据包机制）；
- 不依赖 KubeJS，独立运行；
- 指令完全在局内执行，无需修改配置或重启世界。

## 依赖

| 模组 | 版本 | 是否必装 |
|---|---|---|
| Minecraft Forge | 47.x (1.20.1) | 必装 |
| Curios API | 5.x (1.20.1) | **必装** |
| 车万女仆 | 1.5.x (1.20.1) | 选装（加女仆栏位时需要） |

> 本模组仅依赖 Curios API；没有车万女仆时，也可给其他生物实体加栏位。

## 安装

1. 把 `curiosslot-1.1.0.jar` 放进 `mods` 文件夹；
2. 启动游戏（需要 Curios API 已在 mods 中）；
3. 进世界后使用下方指令。

## 指令用法

需要 OP / 开启作弊（权限等级 2）。

```
/curiosslot set <槽位类型> <数量> <目标实体>
/curiosslot add <槽位类型> <数量> <目标实体>
/curiosslot register <槽位类型> <实体类型ID> [数量]
/curiosslot register <槽位类型> from <目标实体> [数量]
```

| 子命令 | 说明 |
|---|---|
| `set` | 把目标实体的指定槽位数量**设置**为给定值 |
| `add` | 在现有基础上**增加**指定数量的栏位 |
| `register` | 为目标的**整个生物类型**创建该槽位（对同类所有生物生效），并可设置该类型的**默认栏位数量** |

> **set / add** 只能调整实体**已有**的栏位（玩家、车万女仆、以及已通过数据包配置了栏位的生物）。
> 若目标没有该槽位，会明确提示并列出它实际有哪些栏位。
>
> **register** 用来**创建原本不存在的槽位**：它对生物类型（如 `minecraft:cow`）写入 Curios 数据包映射，使该类型的所有生物都获得该槽位。可选 `数量` 参数设置该 (类型, 槽位) 的**默认栏位数量**（不填默认 1，范围 1~64），后续用 register 重新指定会覆盖旧默认值。若该类型已拥有该槽位，register 会拦截创建、只更新默认数量。
>
> **register 按生物类型生效**：只影响该类型**之后生成**的生物，以及重进存档后刷新出栏位的个体；**无法为已生成的无饰品栏的生物单独创建饰品栏**——已存在、且原本没有 Curios 栏位的个体，需要重新生成或重进存档后才会带上新栏位。
>
> **默认数量机制**：已注册且**未被 set/add 修改过**的生物，栏位数量会被强制为默认值；一旦用 `set`/`add` 手动修改过某个生物，则**只保护那一个生物**（按实体 UUID 记录），不再被默认值覆盖。

### 示例

给最近的一只女仆把戒指位设为 4 个：

```
/curiosslot set ring 4 @e[type=touhou_little_maid:maid,limit=1,sort=nearest]
```

给指定名字的女仆额外增加 1 个项链位：

```
/curiosslot add necklace 1 @e[type=touhou_little_maid:maid,name=小夜,limit=1]
```

给所有牛创建戒指栏位（默认 1 个）：

```
/curiosslot register ring minecraft:cow
```

给所有牛创建戒指栏位并设置默认 3 个：

```
/curiosslot register ring minecraft:cow 3
```

### 槽位类型

必须是 Curios 已注册的槽位，常见有：

```
curio / back / belt / body / bracelet / charm / head / hands /
necklace / ring / feet / scroll / spellbook / accessory
```

> 不同整合包/模组注册的槽位类型不同，可用 Curios 的界面或 `data/<mod>/curios/slots/*.json` 查看实际可用槽位。

## 配置（`config/curiosslot-common.toml`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `autoReload` | `true` | register 写入数据包后是否自动 `/reload`（立即生效，但会卡一下）；`false` 则只写映射，需手动 `/reload` 或重进存档生效 |
| `sweepIntervalSeconds` | `10` | 兜底扫描间隔（秒）。生成钩子已让新实体出生即为默认数量，此扫描仅兜底旧实体；`0` 表示关闭 |

> 修改配置后需重启游戏/服务端生效。

## 说明与注意

- 槽位数量是**写进实体数据的**，会随实体保存；重新进游戏仍然保留。
- **register** 写入的数据包位于世界存档的 `datapacks/curiosslot/`，会持续生效；已生成的实体可能需要重进存档刷新后获得新槽位。
- **默认数量由 mod 强制**：实体生成时、以及周期兜底扫描中，会把"已注册且未被 set/add 修改"的实体的该栏位数量设为默认值；`set`/`add` 过的单个生物（按 UUID 记录在 `datapacks/curiosslot/curiosslot_state.json`）不再被默认值覆盖。注意：兜底扫描**只调整该槽位已有的数量**，**无法给"已生成时就没有该栏位"的生物凭空加上该栏位**——这类生物需重进存档或重新生成后才会带上新栏位。
- **适用范围**：set / add 对**玩家**和**车万女仆**等 Curios 集成实体生效，也对其已通过数据包配置栏位的生物生效；要创建普通生物原本没有的槽位，请用 register。
- 只对 `LivingEntity`（活着的生物实体）生效；对非生物实体目标会给出提示。
- 若与其它注册了同名指令的模组/脚本冲突，请移走对应脚本。

## 构建（可选）

本工程使用 ForgeGradle。源码目录包含 Gradle Wrapper：

```
gradlew build
```

产物位于 `build/libs/curiosslot-1.1.0.jar`。

> 注意：本仓库**不含** `libs/`（Curios 的 jar 不在此公开分发，避免分发他人模组）。编译依赖为 `compileOnly fg.deobf(files('libs/curios-forge-5.14.1+1.20.1.jar'))`，如需从源码构建，请自行把对应版本的 Curios jar 放入 `libs/` 目录后执行 `gradlew build`。已编译好的 jar 可直接使用，无需自己构建。

## 许可证

MIT
