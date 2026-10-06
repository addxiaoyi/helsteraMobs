package dev.helstera.ai;

import dev.helstera.api.instance.ModelInstance;

/**
 * 伪装服务：提供 per-instance 模型替换能力。
 *
 * <p>伪装不改变实例的 {@code baseEntity}（原版载体保留），只更换视觉模型。
 * 同一实例同时只能有一个活跃伪装；重复调用 {@code apply} 会覆盖旧的伪装。</p>
 *
 * <p>线程约束：所有方法在主线程调用。</p>
 */
public final class DisguiseService {

    private DisguiseService() {
    }

    /**
     * 为实例应用伪装。
     *
     * @param inst    目标实例
     * @param modelId 伪装目标模型 ID
     * @return 失败原因；成功返回 null
     */
    public static String apply(ModelInstance inst, String modelId) {
        if (!(inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)) {
            return "实例类型不支持伪装";
        }
        return impl.applyDisguise(modelId);
    }

    /**
     * 移除实例的伪装，恢复原始模型。
     *
     * @param inst 目标实例
     * @return 失败原因；成功返回 null（或未伪装时返回 null）
     */
    public static String remove(ModelInstance inst) {
        if (!(inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)) {
            return "实例类型不支持伪装";
        }
        return impl.removeDisguise();
    }

    /** 检查实例当前是否有活跃伪装。 */
    public static boolean isDisguised(ModelInstance inst) {
        if (!(inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)) return false;
        return impl.disguiseModelId != null;
    }

    /** 获取实例当前的伪装模型 ID；未伪装时返回 null。 */
    public static String getDisguiseModelId(ModelInstance inst) {
        if (!(inst instanceof dev.helstera.runtime.instance.ModelInstanceImpl impl)) return null;
        return impl.disguiseModelId;
    }
}