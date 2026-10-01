package dev.helstera.render.display;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骨骼 -> CustomModelData 映射。
 * 每个骨骼获得全局唯一 CMD，资源包构建器按 CMD 生成 item model overrides。
 */
public final class BoneCommandMapping {

    /** PAPER 作为骨骼渲染基础物品（纯净、无外观数据）。 */
    public static final Material BASE_ITEM = Material.PAPER;

    private final Map<String, Integer> byKey = new ConcurrentHashMap<>();
    private final Map<Integer, String> reverse = new ConcurrentHashMap<>();
    private int next = 1;

    /** (modelId, bone) -> CMD；稳定（同模型同骨骼每次构建同 CMD）。 */
    public synchronized int commandData(String modelId, String bone) {
        String key = modelId + "|" + bone;
        return byKey.computeIfAbsent(key, k -> {
            int id = next++;
            reverse.put(id, key);
            return id;
        });
    }

    public synchronized String keyOf(int cmd) {
        return reverse.get(cmd);
    }

    public ItemStack itemFor(String modelId, String bone, int cmd) {
        ItemStack it = new ItemStack(BASE_ITEM);
        var meta = it.getItemMeta();
        meta.setCustomModelData(cmd);
        it.setItemMeta(meta);
        return it;
    }
}
