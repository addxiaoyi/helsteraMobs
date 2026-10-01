package dev.helstera.api.model;

import dev.helstera.api.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 模型骨骼（不可变）。pivot 为模型空间中的旋转轴心。
 */
public interface Bone {

    String name();

    /** 父骨骼名；根骨骼返回 null。 */
    String parent();

    /** 旋转轴心（模型空间，未乘全局缩放）。 */
    Vec3 pivot();

    /** 骨骼上的立方体列表。 */
    List<ModelCube> cubes();

    /** 挂接点：名称 -> 相对骨骼 pivot 的偏移。 */
    Map<String, Vec3> attachPoints();

    /** 子骨骼。 */
    List<Bone> children();

    /** 骨骼碰撞盒（若启用骨骼碰撞），可为 null。 */
    ModelHitbox boneHitbox();
}
