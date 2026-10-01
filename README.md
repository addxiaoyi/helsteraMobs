# helsteraMobs

Minecraft（Paper / Spigot）模型引擎插件。采用「资源包 + Display 实体（ItemDisplay / TextDisplay）」渲染方案，纯 Bukkit/Paper API，无 NMS 依赖。

## 功能

- 模型格式 `helstera-v1`：`manifest.yml` + `model.json` + `animations.json` + 纹理，支持骨骼层级、动画关键帧、父子变换。
- 渲染：骨骼 → ItemDisplay 层级，Interaction 碰撞体，玩家可见性订阅（hideEntity），统一 Tick 调度器（批量队列 / 预算 / LOD）。
- 资源包构建：Box-UV → 模型 JSON、CustomModelData overrides、zip 打包、SHA1、下发。
- AI 有限状态机（空闲 / 巡逻 / 追击 / 攻击 / 受伤 / 逃跑 / 施法 / 死亡）+ 感知器 + 行为注册表。
- 网页开发器（JDK HttpServer 自研，本机监听 + 令牌认证）：模型列表 / 预览 / 动画播放 / 生物配置编辑 / 校验 / 保存 / 回滚 / 审计。
- 外部插件适配（MythicMobs / ItemAdder / CraftEngine 反射检测）与迁移中心（四插件导入器 + 自动备份）。

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

# 分阶段出包（仅含"已完成"模块，产物名 HelsteraMobs-<n>.0.jar）
mvn -o clean package -P phase1   # 1.0：核心 api + core
mvn -o clean package -P phase2   # 2.0：+ runtime + render-paper
mvn -o clean package -P phase3   # 3.0：+ resourcepack + ai
mvn -o clean package -P phase4   # 4.0：+ integrations + migration
# 默认（不激活任何 -P）= 完整版 5.0
```

产出的 uber jar 放入服务器 `plugins/` 目录即可。

## 网页开发器

默认监听 `0.0.0.0:8765`（所有网卡），启动日志会打印带令牌的访问地址：

```
→ 远程访问: http://<服务器IP>:8765/?token=xxxx
```

- 同机访问：保持 `config.yml` 的 `web.host: 127.0.0.1`，打开 `http://127.0.0.1:8765/`。
- 跨机访问：`web.host: 0.0.0.0`，或用命令 `/helstera web start 0.0.0.0` 立即按 0.0.0.0 重启。
- 排障：`/helstera web doctor` 打印各网卡真实访问地址与防火墙提示；`/helstera web firewall` 尝试添加放行规则（需管理员权限）。
- 注意 `0.0.0.0` 会监听所有网卡，请配合防火墙——任何能连到该端口者凭令牌即可访问。

## 许可证

见 [LICENSE](LICENSE)。
