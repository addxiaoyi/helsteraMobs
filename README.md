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
- 刷怪点（spawners.yml）：定时 + 半径随机 + 存活上限 + 累计上限 + 玩家门控 + 世界绑定；所有刷怪点共用一个调度任务。
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

## 许可证

见 [LICENSE](LICENSE)。
