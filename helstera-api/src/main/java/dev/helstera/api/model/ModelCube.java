package dev.helstera.api.model;

import dev.helstera.api.Vec3;

/**
 * 模型立方体（不可变）。origin 为立方体最小角，size 为三轴尺寸，
 * uv 为该立方体在纹理页中的 Box-UV 左上角坐标（单位=纹理像素）。
 */
public record ModelCube(Vec3 origin, Vec3 size, int[] uv, boolean mirror) {
}
