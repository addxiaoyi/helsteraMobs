package dev.helstera.api.model;

/**
 * 碰撞盒（单位=格）。第一版只提供整体碰撞盒。
 */
public record ModelHitbox(double width, double height) {

    public static final ModelHitbox DEFAULT = new ModelHitbox(0.9, 1.9);
}
