# Curios Slot

一个轻量 Forge 模组（Minecraft **1.20.1** / Forge **47.x**），允许你在游戏内**通过指令为实体添加或设置 Curios 饰品栏位**。

- 为**玩家**、**车万女仆**等 Curios 集成实体增加 Curios 饰品栏位；
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

1. 把 `curiosslot-1.0.0.jar` 放进 `mods` 文件夹；
2. 启动游戏（需要 Curios API 已在 mods 中）；
3. 进世界后使用下方指令。

## 指令用法

需要 OP / 开启作弊（权限等级 2）。

```
/curiosslot set <槽位类型> <数量> <目标实体>
/curiosslot add <槽位类型> <数量> <目标实体>
```

| 子命令 | 说明 |
|---|---|
| `set` | 把目标实体的指定槽位数量**设置**为给定值 |
| `add` | 在现有基础上**增加**指定数量的栏位 |

### 示例

给最近的一只女仆把戒指位设为 4 个：

```
/curiosslot set ring 4 @e[type=touhou_little_maid:maid,limit=1,sort=nearest]
```

给指定名字的女仆额外增加 1 个项链位：

```
/curiosslot add necklace 1 @e[type=touhou_little_maid:maid,name=小夜,limit=1]
```

### 槽位类型

必须是 Curios 已注册的槽位，常见有：

```
curio / back / belt / body / bracelet / charm / head / hands /
necklace / ring / feet / scroll / spellbook / accessory
```

> 不同整合包/模组注册的槽位类型不同，可用 Curios 的界面或 `data/<mod>/curios/slots/*.json` 查看实际可用槽位。

## 说明与注意

- 槽位数量是**写进实体数据的**，会随实体保存；重新进游戏仍然保留。
- **适用范围**：指令对**玩家**和**车万女仆**等 Curios 集成实体生效。普通生物（牛、僵尸等）在 Curios 中不持有饰品栏 handler，Curios 本身不支持为其新增槽位类型（Curios 的底层限制），对其使用会提示"目标实体没有 Curios 饰品栏"。
- 只对 `LivingEntity`（活着的生物实体）生效；对非生物实体目标会给出提示。
- 若与其它注册了同名指令的模组/脚本冲突，请移走对应脚本。

## 构建（可选）

本工程使用 ForgeGradle。源码目录包含 Gradle Wrapper：

```
gradlew build
```

产物位于 `build/libs/curiosslot-1.0.0.jar`。

> 注意：本仓库**不含** `libs/`（Curios 的 jar 不在此公开分发，避免分发他人模组）。编译依赖为 `compileOnly fg.deobf(files('libs/curios-forge-5.14.1+1.20.1.jar'))`，如需从源码构建，请自行把对应版本的 Curios jar 放入 `libs/` 目录后执行 `gradlew build`。已编译好的 jar 可直接使用，无需自己构建。

## 许可证

MIT
