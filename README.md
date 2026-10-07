# helsteraMobs

Minecraft（Paper / Spigot）模型引擎插件。采用「资源包 + Display 实体（ItemDisplay / TextDisplay）」渲染方案，纯 Bukkit/Paper API，无 NMS 依赖。

## 功能

- 模型格式 `helstera-v1`：`manifest.yml` + `model.json` + `animations.json` + 纹理，支持骨骼层级、动画关键帧、父子变换。
- 渲染：骨骼 → ItemDisplay 层级，Interaction 碰撞体，玩家可见性订阅（hideEntity），统一 Tick 调度器（批量队列 / 预算 / LOD）。
- 资源包构建：Box-UV → 模型 JSON、CustomModelData overrides、zip 打包、SHA1、下发。
- AI 有限状态机（空闲 / 巡逻 / 追击 / 攻击 / 受伤 / 逃跑 / 施法 / 死亡）+ 感知器 + 行为注册表。
- 目标选择器（Targeter）：最近 / 最远 / 随机 / 最低血量 / 最高血量 / 仅玩家 / 仅怪物，并驱动 `aoe-damage`、`teleport-targets`、`effect-targets`、`ignite-targets`、`knockback-targets`、`message-targets` 等多目标动作。
- 掉落表（loot.yml）：权重概率、数量区间、幸运值加成、附魔 / 自定义名 / CustomModelData；死亡时自动投掷并可计入抢夺等级。
- 免疫与伤害倍率（对标 MythicMobs `Immunities` / `DamageModifiers`）：按档案配置，支持精确 cause 与类别两层匹配、`negate` 免疫、倍率与负倍率回血、单个倍率跨 tick 不衰减、条件化生效；详见下方「免疫 / 伤害倍率」。
- 网页开发器（Javalin 6 + Jackson）：模型列表 / 预览 / 动画播放 / 生物配置编辑 / 校验 / 保存 / 回滚 / 审计 / 模型 zip 导入导出；**技能**（skills.yml）、**掉落表**（loot.yml，含掷骰预览）、**刷怪点**（spawners.yml）可视化编辑；SSE 实时推送重载事件，保存后无需手动刷新。
- 外部插件适配（MythicMobs / ItemAdder / CraftEngine 反射检测）与迁移中心（四插件导入器 + 自动备份）。
- 刷怪点（spawners.yml）：定时 + 半径随机 + 存活上限 + 累计上限 + 玩家门控 + 世界绑定；所有刷怪点共用一个调度任务。**支持 `ai-profile` 字段覆盖 mob 档案的 AI 配置**，无需修改原档案即可指定不同的行为配置。
- MythicMobs 模型 mechanic 与条件（反射注册，签名变动自动降级）：`modelspawn` / `modelremove` / `modelplay` / `modelstop` / `modelscale` / `modelmount` / `modelunmount` / `modelheal`，以及 `modelspawned` / `modelremoved` / `modelplaying`。

## 模块

| 模块 | 说明 |
| --- | --- |
| helstera-api | 公共 API |
| helstera-core | 模型解析 / 校验 / 注册表 |
| helstera-runtime | 实体运行时 |
| helstera-render-paper | Paper 端渲染 |
| helstera-resourcepack | 资源包构建 |
| helstera-ai | AI 行为 |
| helstera-integrations | 外部插件适配 |
| helstera-web | 网页开发器 |
| helstera-migration | 迁移中心 |
| helstera-platform | 平台校验 |
| helstera-plugin | 插件主模块（打包 uber jar） |

## 构建

要求：JDK 21、Maven 3.9+（离线构建，`~/.m2` 需预缓存 paper-api、adventure、joml、gson、snakeyaml 等）。

```bash
# 完整版（5.0，含网页开发器）
mvn -o clean install

# 跑测试（全仓库 12 模块，589 个用例）
mvn -o test

# 分阶段出包（仅含"已完成"模块，产物名 HelsteraMobs-<n>.0-<variant>.jar）
mvn -o clean package -P phase1   # 1.0：核心 api + core
mvn -o clean package -P phase2   # 2.0：+ runtime + render-paper
mvn -o clean package -P phase3   # 3.0：+ resourcepack + ai
mvn -o clean package -P phase4   # 4.0：+ integrations + migration
# 默认（不激活任何 -P）= 完整版 5.0（profile 定义在 helstera-plugin/pom.xml，不在根 pom）
```

产物实测体积（2026-06）：phase1 ≈ 0.89 MB、phase2 ≈ 0.95 MB、phase3 ≈ 1.19 MB、
phase4 ≈ 1.25 MB、full ≈ 9.50 MB。

各阶段 jar 的名字带 `-<variant>` 后缀而不是共用同一个名字——共用会导致按阶段验证
时静默覆盖，拿到的却是上一轮的残缺 jar。

产出的 uber jar 放入服务器 `plugins/` 目录即可。

所有 helstera 模块在 `helstera-plugin` 中以 `provided` 作用域声明（只进编译期类路径），
实际打进 jar 的模块由 `-P` profile 决定。因此各阶段都能编译通过，
运行期缺失的子系统由 `onEnable` 的 `try/catch` 优雅降级。

## 网页开发器

默认监听 `0.0.0.0:8765`（所有网卡），启动日志会打印带令牌的访问地址：

```
→ 远程访问: http://<服务器IP>:8765/?token=xxxx
```

- 同机访问：保持 `config.yml` 的 `web.host: 127.0.0.1`，打开 `http://127.0.0.1:8765/`。
- 跨机访问：`web.host: 0.0.0.0`，或用命令 `/helstera web start 0.0.0.0` 立即按 0.0.0.0 重启。
- 排障：`/helstera web doctor` 打印各网卡真实访问地址与防火墙提示；`/helstera web firewall` 尝试添加放行规则（需管理员权限）。
- 注意 `0.0.0.0` 会监听所有网卡，请配合防火墙——任何能连到该端口者凭令牌即可访问。

## 免疫 / 伤害倍率

对标 MythicMobs 的 `Immunities` / `DamageModifiers`，写在**行为档案**上（与 `faction`、
`region`、`pathfind`、`phases` 同处），mobs/*.yml 的 `ai` 节可整体覆盖档案级配置。

```yaml
ai:
  profiles:
    烈焰领主:
      immunities: [PROJECTILE]      # 免疫箭与所有弹丸
      damage-modifiers:
        fire: 0.5                   # 火伤减半（类别，覆盖 FIRE/FIRE_TICK/LAVA/...）
        drowning: 0.25              # 溺水只剩四分之一
        entity-attack: -1.0         # 近战伤害转为回血
        PROJECTILE:
          when: health-above 0.3    # 仅血量高于 30% 时免疫
          immune: true
```

### 排查前必须知道的五条规则

这五条决定「配置是否生效」，不看代码无法自行推断：

1. **倍率乘的是事件原始伤害**，不是已扣护甲后的最终值。因此 `0.5` 不会逐次衰减成
   `0.25` / `0.125`——这是有意为之。
2. **优先级：精确 cause > 类别**。写了 `FIRE_TICK` 就只挡 `FIRE_TICK`，类别 `fire` 仍管其余。
3. **同类目多条命中时，先写的那条生效**。书写顺序即优先级，保序是有意设计。
4. **同名键只保留一条**。`fire` 同时能当 cause 名和类别名，两种解释都会注册，
   但**同一键重复声明会被后者顶掉**——不会叠加。
5. **条件不成立 = 该条规则不存在**，并继续向下匹配下一条，而不是把伤害冻结。
   条件名用 `when`（单条）或 `conditions`（多条，全部满足才生效）。

### 静默失效的几种情况

免疫配错在服务端**完全没有症状**——规则不匹配与「没配这条」在现象上完全一致。
以下情况不会有任何报错或异常：

- 伤害类型名拼错（既不是 cause 也不是类别）→ 该条被跳过
- `when` / `conditions` 里的条件名不存在 → 条件恒不成立，该条永不生效
- 免疫监听器未注册（仅在 AI 层未启用等异常路径）→ 全部免疫规则静默失效

对应的排查入口：

```bash
/helstera immunity            # 逐条规则 + 逐 cause 试算（按 10 点原始伤害）
/helstera immunity <档案名>   # 只看某个档案
/helstera check               # 体检，4.3 段报告装载期告警与监听器状态
```

网页开发器「运营工具 → 免疫 / 伤害倍率诊断」面板提供同样的信息（`GET /api/immunity`）。

### 回血语义

负倍率即回血，走「伤害归零 + 显式加血」，**不依赖服务端对负伤害的处理差异**，
因此不会静默失效、也不会双重治疗。加血夹到 `maxHealth`——`setHealth` 越界会被服务端
夹到 0，也就是把生物打死。

### 从 MythicMobs 迁移

迁移中心会自动落地 `Immunities` 与 `DamageModifiers`（含三种写法：裸数字、
带 `multiplier` 的节、带 `conditions` 的条目列表），并把条件名换算成 helstera 的写法
（`?health{<50%}` → `health-below 0.5`）。

不换算会让条件永远匹配不上，而条件求值是 fail-closed——表现为「配了条件免疫，
永远不免疫」，且没有任何报错。无等价物的条件（如 `?onGround`）会**原样保留并在
报告中告警**，不会静默丢弃。

## 仇恨与连杀

### 仇恨（threat）

`ThreatTable` 按玩家 UUID 记录，威胁值 = 伤害量 × 距离权重，并按秒指数衰减。
目标选择由它决定，因此「拉仇恨」「切目标」都是可配置行为而非巧合。

```yaml
ai:
  profiles:
    坦克 Boss:
      threat-enabled: true
      threat-decay: 0.05
      threat-distance-weight: 10
      skills:
        - trigger: on-target
          priority: 10
          require:
            - threat-above 800        # 某人的仇恨超过 800 才进入狂暴
          actions:
            - set-nameplate "&c&l狂暴！"
            - effect-self speed 2 200
```

**条件**：`threat-above <n>` / `threat-below <n>` / `has-threat [n]` /
`threat-targets-at-least <n>`——判定对象是**当前目标**在该实例仇恨表中的值。

**动作**：`threat-add <n>`（给当前目标加仇恨）/ `threat-clear [true]`（`true`
只清当前目标，缺省清空整表）/ `threat-focus`（把当前目标顶成首要目标）。

排查前要知道的三条：

- **档案必须开 `threat-enabled`**，否则没有仇恨表，四条条件恒为 false。
  这与「仇恨值确实是 0」在现象上完全一致。
- **仇恨按实例存储**。同一个玩家对不同 Boss 的仇恨互不相同，
  条件读的是「这个实例眼中该玩家的值」。
- `threat-focus` 叠加一个足够大的增量，**不直接改写仇恨值**——直接赋值会
  绕过距离权重与衰减，破坏仇恨平衡。

目标选择器 `threat` 按仇恨降序排列，等仇恨时按距离兜底（否则模型会在
候选间反复横跳）。**未注入仇恨数据源时它返回空候选，不会悄悄退回「最近」**。

### 连杀（kill-streak）

按玩家 UUID 记连续击杀，**不按实例**——同一玩家杀 10 只不同的怪是 10 次连杀，
不是 10 个各为 1 的实例分数。默认窗口 30 秒，用 `System.nanoTime` 计时
（改系统时间不会让连杀算错或永不重置）。

窗口**惰性求值**：读取时比对时间戳，超时即视为 0。因此不存在
「定时器漏跑导致连杀不重置」这类只在卡顿时偶发的故障。

**条件**：`kill-streak-at-least <n>` / `kill-streak-active`
**动作**：`kill-streak-reset`（领奖一次后打回原形）

刻意**不提供「直接设连杀数」的动作**：连杀只能由真实击杀累积，
否则 `on-kill-player` 的奖励可以凭空刷出来。

判定对象是「击杀了该实例的玩家的连杀数」。因此：

- 只有**玩家**击杀计入；陷阱、投射物、环境致死都拿不到 Player 击杀者
- 该实例从未被玩家击杀过时判定为 0，**不会**在首次见面时就触发连杀奖励

## 目标选择器（targeter）

技能里的 `targeter:` 决定「挑谁」。共 12 个：

| 名字 | 排序依据 |
| --- | --- |
| `nearest` / `farthest` | 距离由近到远 / 由远到近 |
| `random` | 随机打乱（不排序，避免可预测行为） |
| `players` / `mobs` / `living` | 类型过滤后按距离 |
| `lowest-health` / `highest-health` | **绝对血量** |
| `vulnerable` / `lowest-health-percent` | **血量比例**最低（等同 MM 的 vulnerable） |
| `highest-health-percent` | 血量比例最高 |
| `threat` | 仇恨值降序 |

两个容易踩的点：

- **`lowest-health` 与 `vulnerable` 不是一回事**。前者按绝对值，在混血队伍里几乎恒等于「挑玩家」；后者按比例，满血的坦克是否脆弱取决于比例而非数值。
- **`threat` 需要档案开 `threat-enabled`**，否则返回**空候选**而不是悄悄退回「最近」。这是刻意的——回落会让「按仇恨选目标」静默变成「选最近的」，而配置看上去完全正常。

等分时一律按距离兜底，保证排序确定：否则每 tick 遍历顺序的微小变化会让模型在候选之间反复横跳，表现为周期性抽搐转向。

## Boss 血条

写在 `mobs/<档案>.yml` 的 `bossbar` 节，随档案走。

```yaml
ai:
  profiles:
    巨龙:
      bossbar:
        enabled: true
        title: "&c远古巨龙"
        range: 64
        thresholds: [0.5, 0.2]
        colors: [GREEN, RED]
```

| 键 | 说明 |
| --- | --- |
| `enabled` | 关闭时不显示任何血条（默认值） |
| `title` | 主标题；留空则用档案名，再留空用「Boss」 |
| `range` | 可见距离；`0` 表示不限 |
| `thresholds` / `colors` | 分段配色，两者**必须等长**，否则告警并忽略分段 |

三段式接线：生成时挂条、受伤时更新、死亡时隐藏。

**四种显示里三种可用**：名称 + 血量、分段变色、当前阶段名（读档案的 `phases`）。
**技能读条未接**——技能执行流程没有暴露施法进度，需要 hook 进 `SkillService` 才能取到。

### 排查前必须知道的三条

- **阶段名来自 `phases` 配置**。档案没写 `phases` 时，血条只显示名称和血量，不会出现 `[阶段]`。
- **`thresholds` 与 `colors` 数量必须一致**。不一致会告警并**忽略整段配色**，血条退回默认绿色，而不是部分生效。
- **比例低于所有档下界时回落到最低档**。所以把下界都写高时，「只剩一丝血」会显示最低档色而不是满血色——这是刻意的，否则濒死观感与实际相反。

## 阶段播报（Announcement）

写在 `profiles.<name>.phases[].announce`（文字）及可选的 `sound` / `particle` / `commands`。
播报范围是 Boss 周围 32 格内的玩家，而非全服广播。

```yaml
phases:
  - id: enraged
    min: 0
    max: 75
    announce: "&4[警告] Boss 进入狂暴状态！"
    sound: ENTITY_WITHER_SPAWN
    particle: DRIP_LAVA
    commands:
      - "say %mob-name% is now in phase %phase%"
      - "effect give @a nearby 8 speed 10 1"
  - id: dead_phase
    min: 75
    max: 100
    announce: ""          # 纯音效无文字
    sound: BLOCK_NOTE_BLOCK_PLING
```

| 字段 | 说明 |
| --- | --- |
| `announce` | 文字消息（支持 `%phase%` `/ %hp%` `/ %mob%` 等占位符，同阶段切换公告） |
| `sound` | Bukkit `Sound` 枚举名，在 Boss 位置播放 |
| `particle` | Bukkit `Particle` 枚举名，在 Boss 位置发射 64 个粒子 |
| `commands` | 列表；每条通过控制台执行，占位符同样展开 |

**规则**：
- 四项独立：文字为空仍可播 sound；command 为空仍可播粒子。
- 任何一项执行失败只打 warning，不影响其他项。
- 播报半径固定 32 格，不可配置——这是手感调校后的结论，避免野外 Boss 切阶段刷屏。

## Mob Levels 等级缩放

写在 `mobs/<档案>.yml` 的 `ai` 节里，**不是**写在 `ai.profiles.<name>` 里。

```yaml
ai:
  profile: 巨龙
  level: 10
  levels:
    - property: health
      base: 1000
      growthPerLevel: 1.05
    - property: damage
      base: 10
      growthPerLevel: 1.10
    - property: speed
      base: 0.3
      growthPerLevel: 1.02
```

| 键 | 说明 |
| --- | --- |
| `level` | 当前等级；1 = 基础档，不触发缩放 |
| `levels` | 每个等级的缩放配置列表；为空时不缩放任何属性 |
| `levels[*].property` | 属性名：`health` / `damage` / `speed`（缺省为其他值时静默忽略） |
| `levels[*].base` | 该属性在 1 级时的基准值 |
| `levels[*].growthPerLevel` | 每级的增长倍数；1.0 = 不随等级变化 |

**缩放公式**：`实际值 = base × growthPerLevel^(level-1)`

影响范围：
- **生命值**：生成时 `setMaxHealth` 与 `setHealth` 按缩放后值写入实体
- **攻击伤害**：`hitTarget()` 结算时用 `profile.attackDamage × damageScale`
- **移速**：`moveBy()` 用 `profile.moveSpeed × speedScale`
- **血条标题**：显示 `Lv.N 名称 [阶段]`，N 来自实例的 level 字段

排查点：
- **`level: 1` 或省略** 时，`growthPerLevel^0 = 1.0`，所有属性不缩放——这与「未启用等级系统」的语义一致，不会因为少写一个字段而报错
- **`growthPerLevel < 1`** 会被原样保留：若写 `0.5`，等级越高属性越低（衰减而非增长）。这是有意为之，让作者能表达「幼体 weaker、成体 stronger」的反向设计
- **属性名拼错**（如写 `hp` 而非 `health`）会静默忽略该属性，不影响其他属性

## 对话与电影脚本（Dialogs & Cinematics）

写在 `dialogs.yml` 的 `dialogues` 节里，通过 `start-dialogue` 技能动作触发。

```yaml
dialogues:
  guard_intro:
    cinematics:
      greeting:
        commands:
          - say 守卫 [严肃] 站住！此处禁止通行。
          - wait 60
          - look 90 0
          - say 守卫 [警告] 再不后退我将呼叫支援。
          - wait 80
          - say 守卫 [愤怒] 既然你不听……攻击！
      farewell:
        commands:
          - say 守卫 [疲惫] 看来你比我想象的要强……走吧。
```

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `say` | `<说话者> <文本>` | 向附近 16 格玩家发送聊天消息 |
| `move` | `<dx> <dy> <dz> <speed>` | 移动实例载体（世界坐标偏移） |
| `look` | `<yaw> <pitch>` | 设置实例载体朝向 |
| `wait` | `<ticks>` | 等待指定 tick 数（20 ticks = 1 秒） |

在 mobs/*.yml 中通过 `ai.triggers` 引用：

```yaml
ai:
  profile: default
  triggers:
    on-spawn:
      do: [start-dialogue guard_intro:greeting]
```

排查点：
- **同一实例同时只运行一条 cinematic**：重复触发会打断并重新开始
- **命令参数不足时静默跳过**：与技能系统一致，不会报错
- **未知命令记录告警**：不会中断整个 cinematic

## 技能读条（Cast Progress）

写在 `skills.yml` 的技能定义里，通过 `start-cast` 动作启动，读条期间 Boss 血条显示进度百分比。

```yaml
skills:
  fire-blast:
    cast-duration: 3s          # 读条时长
    cast-label: "蓄力中"        # 标题后缀文本
    on-decision:
      - start-cast fire-blast  # 开始读条
      - wait 3s                # 等待读条完成
      - aoe-damage players 8 1 20  # 读条结束后造成伤害
  cancel-fire-blast:
    on-decision:
      - cancel-cast            # 取消读条
```

| 字段 | 说明 |
| --- | --- |
| `cast-duration` | 读条时长（`3s` / `1500ms` / `90t`）；0 或省略 = 无读条 |
| `cast-label` | 血条标题后缀（如 "蓄力中"）；默认 "施法中" |

**配套动作**：
- `start-cast <技能名>`：启动读条计时，不执行技能动作
- `cancel-cast`：取消当前实例的所有活跃读条
- 死亡/ despawn 时自动清除该实例的所有读条

血条显示格式：`Lv.N 名称 [阶段] 蓄力中 67%`

## 刷怪点 AI 门控

`spawners.yml` 的每个刷怪点可加 `ai-profile` 字段，覆盖 mob 档案的 AI 配置，
无需修改原档案即可为同一 mob 指定不同的行为配置。

```yaml
spawners:
  elite_spawner:
    mob: dragon
    x: 0
    y: 64
    z: 0
    radius: 20
    max-alive: 2
    ai-profile: "dragon_elite"   # 覆盖 dragon.yml 的 AI 配置
  normal_spawner:
    mob: dragon
    x: 100
    y: 64
    z: 100
    radius: 15
    # 不写 ai-profile 则沿用 dragon.yml 的默认 AI 配置
```

优先级：`ai-profile`（刷怪点）> `ai.profile`（mobs/*.yml 的 ai 节）> `default`。

## 触发器接线状态

`/helstera check` 会区分三种情况，而不是笼统说「未知名」：

| 状态 | 含义 |
| --- | --- |
| 已接线 | 写进 `mobs/*.yml` 会正常触发 |
| 可写但未接线 | 名字认得，写了**不会报错也不会触发** |
| 无法识别 | 名字拼错了，改键名即可 |

**全部已接线**：所有触发器（含 `on-pre-target`、`on-damage-negation`、`on-death-skill`）均已接入真实事件来源。

### MM 条件补全

以下 MythicMobs 常用条件现已在 helstera 中可用：

| MM 条件 | helstera 写法 | 说明 |
| --- | --- | --- |
| `?onGround` | `on-ground` | 实体站在固体方块上 |
| `?inWater` | `in-water` | 实体处于水中 |
| `?inLava` | `in-lava` | 实体处于岩浆中 |
| `?isSneaking` | `is-sneaking` | 实体是否潜行 |
| `?isGlowing` | `is-glowing` | 实体是否发光 |
| `?facing-{target}` | `facing-target [角度]` | 实体朝向目标（默认 ±45° 容差） |

配置示例：

```yaml
triggers:
  on-spawn:
    require: [on-ground]          # 只在地面生成时触发
    do: [sound ENTITY_WITHER_SPAWN]
  on-damage-negation:
    do: [ignite-targets players 4 1]  # 被免疫时点燃攻击者
```

写一个未接线的触发器不会有任何提示，所以配置体检应当习惯性跑一遍。

## 技能队列与优先级

当同一实例在同一 tick 内触发多个技能时，系统按 `priority` 字段降序排队执行，高优先级先执行完再执行下一个。

```yaml
skills:
  urgent-heal:
    priority: 100          # 高优先级：紧急回复
    on-decision:
      - heal-target players 20
  display-effect:
    priority: 10           # 低优先级：装饰性效果
    on-decision:
      - particle heart 5
```

- 默认 `priority` 为 0，数值越大越先执行。
- 队列仅在多技能同时触发时激活；单次触发不受影响。
- 队列中的技能独立计算冷却，已在冷却中的技能不入队。

### 关于 `on-entity-shoot`

该触发器**可用**：载体的 `entity.type` 可以是 `player`，这类载体确实能射箭，
由 `EntityShootBowEvent` 驱动。早期版本曾因「模型实例不会自己发射弹丸」
而标为未接线，导致体检命令劝服用户放弃一个本来能用的机制——现已修正。

## 许可证

见 [LICENSE](LICENSE)。
