helsteraMobs Example Pack (Emberling)

assets/helstera/models/entity/emberling.json  —— Blockbench 实体模型（骨骼：head/body/arms/legs/tail）
assets/helstera/textures/entity/emberling.png —— 64x64 贴图（Box-UV 布局）

插件运行时会把这个目录解析成 models/example/emberling/（manifest.yml + model.json + 动画），
再由 ResourcePackBuilder 按骨骼逐块生成物品模型，通过 paper 的 custom_model_data 覆盖挂到显示实体上。
