package dev.helstera.core.model;

import dev.helstera.api.Vec3;
import dev.helstera.api.model.Bone;
import dev.helstera.api.model.ModelCube;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.core.animation.AnimationClip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型定义与骨骼的不可变实现。
 */
public final class ModelDefinitionImpl implements ModelDefinition {

    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final double scale;
    private final String defaultAnimation;
    private final List<Bone> roots;
    private final List<Bone> flat;
    private final Map<String, Bone> byName;
    private final ModelHitbox hitbox;
    private final Map<String, Map<String, Vec3>> attachPoints;
    private final Path sourceDirectory;
    private final String sourceFormat;
    private final Map<String, AnimationClip> animations;
    private final List<Path> textures;

    public ModelDefinitionImpl(String id, String name, String version, String author, double scale,
                               String defaultAnimation, List<Bone> roots, ModelHitbox hitbox,
                               Path sourceDirectory, String sourceFormat,
                               Map<String, AnimationClip> animations, List<Path> textures) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.scale = scale;
        this.defaultAnimation = defaultAnimation;
        this.roots = List.copyOf(roots);
        List<Bone> flatList = new ArrayList<>();
        Map<String, Bone> map = new LinkedHashMap<>();
        for (Bone b : roots) collect(b, flatList, map);
        this.flat = Collections.unmodifiableList(flatList);
        this.byName = Collections.unmodifiableMap(map);
        this.hitbox = hitbox;
        this.attachPoints = collectAttachPoints();
        this.sourceDirectory = sourceDirectory;
        this.sourceFormat = sourceFormat;
        this.animations = Collections.unmodifiableMap(animations);
        this.textures = List.copyOf(textures);
    }

    private void collect(Bone b, List<Bone> out, Map<String, Bone> map) {
        out.add(b);
        map.put(b.name(), b);
        for (Bone c : b.children()) collect(c, out, map);
    }

    private Map<String, Map<String, Vec3>> collectAttachPoints() {
        Map<String, Map<String, Vec3>> out = new LinkedHashMap<>();
        for (Bone b : flat) {
            if (!b.attachPoints().isEmpty()) out.put(b.name(), b.attachPoints());
        }
        return out;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name == null ? id : name; }
    @Override public String version() { return version; }
    @Override public String author() { return author; }
    @Override public double scale() { return scale; }
    @Override public String defaultAnimation() { return defaultAnimation; }

    @Override
    public List<String> animationNames() {
        return List.copyOf(animations.keySet());
    }

    @Override public List<Bone> roots() { return roots; }
    @Override public List<Bone> allBones() { return flat; }
    @Override public Bone bone(String name) { return byName.get(name); }
    @Override public ModelHitbox hitbox() { return hitbox; }
    @Override public Map<String, Map<String, Vec3>> attachPoints() { return attachPoints; }
    @Override public Path sourceDirectory() { return sourceDirectory; }
    @Override public String sourceFormat() { return sourceFormat; }

    public Map<String, AnimationClip> animations() { return animations; }

    public AnimationClip animation(String name) { return animations.get(name); }

    /** 纹理文件（绝对路径）。 */
    public List<Path> textures() { return textures; }

    /** 骨骼不可变实现。 */
    public static final class BoneImpl implements Bone {
        private final String name;
        private final String parent;
        private final Vec3 pivot;
        private final Vec3 restRotation;
        private final List<ModelCube> cubes;
        private final Map<String, Vec3> attachPoints;
        private final List<Bone> children = new ArrayList<>();
        private final ModelHitbox boneHitbox;
        private final String texture;

        public BoneImpl(String name, String parent, Vec3 pivot, Vec3 restRotation,
                        List<ModelCube> cubes, Map<String, Vec3> attachPoints,
                        ModelHitbox boneHitbox, String texture) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.restRotation = restRotation;
            this.cubes = List.copyOf(cubes);
            this.attachPoints = Map.copyOf(attachPoints);
            this.boneHitbox = boneHitbox;
            this.texture = texture;
        }

        public void addChild(Bone child) {
            children.add(child);
        }

        @Override public String name() { return name; }
        @Override public String parent() { return parent; }
        @Override public Vec3 pivot() { return pivot; }
        public Vec3 restRotation() { return restRotation; }
        @Override public List<ModelCube> cubes() { return cubes; }
        @Override public Map<String, Vec3> attachPoints() { return attachPoints; }
        @Override public List<Bone> children() { return Collections.unmodifiableList(children); }
        @Override public ModelHitbox boneHitbox() { return boneHitbox; }
        /** 骨骼使用的纹理键（对应 manifest textures 列表索引或文件名），可为 null。 */
        public String texture() { return texture; }
    }
}
