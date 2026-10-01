package dev.helstera.api.model;

import dev.helstera.api.Vec3;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

/**
 * 已加载的模型定义（不可变）。模型 ID 形如 {@code pack/model}。
 * 线程约束：读取可在任意线程；生成实例必须在主线程。
 */
public interface ModelDefinition {

    /** 模型稳定 ID，例如 {@code dragon/elder_dragon}。 */
    String id();

    String name();

    String version();

    String author();

    /** 全局缩放系数（1.0 = 原始大小）。 */
    double scale();

    /** 默认待机动画名（可能为 null）。 */
    String defaultAnimation();

    /** 已声明的动画名集合。 */
    List<String> animationNames();

    /** 根骨骼列表（骨骼树，通过 {@link Bone#children()} 遍历）。 */
    List<Bone> roots();

    /** 所有骨骼（扁平，含根）。 */
    List<Bone> allBones();

    /** 按名称查找骨骼。 */
    Bone bone(String name);

    /** 整体碰撞盒（宽、高，单位=格）。 */
    ModelHitbox hitbox();

    /** 挂接点：骨骼名 -> (挂接点名 -> 模型空间坐标)。 */
    Map<String, Map<String, Vec3>> attachPoints();

    /** 模型所在资源目录（用于纹理定位等）。 */
    java.nio.file.Path sourceDirectory();

    /** 来源格式标签，例如 "helstera-v1"。 */
    String sourceFormat();

    /** 纹理文件列表（绝对路径）。 */
    List<Path> textures();
}
