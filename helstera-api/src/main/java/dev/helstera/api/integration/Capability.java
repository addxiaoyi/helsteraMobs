package dev.helstera.api.integration;

/** 适配器能力标记。 */
public enum Capability {
    /** 可以为 MythicMobs 生物绑定模型。 */
    MOB_MODEL_BINDING,
    /** 提供模型技能（modelspawn 等）。 */
    SKILLS,
    /** 提供模型条件。 */
    CONDITIONS,
    /** 事件触发转发。 */
    EVENTS,
    /** 自定义物品 ID 解析（ItemAdder/CraftEngine）。 */
    CUSTOM_ITEM_RESOLVE,
    /** 资源包合并。 */
    RESOURCEPACK_MERGE
}
