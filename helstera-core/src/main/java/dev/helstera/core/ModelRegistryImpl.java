package dev.helstera.core;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelLoadedEvent;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.core.parse.ModelParser;
import dev.helstera.core.validate.ModelValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 模型注册表实现：异步解析/校验，主线程注册；重复 ID 拒绝并给出具体位置。
 */
public final class ModelRegistryImpl implements ModelRegistry {

    private final Map<String, ModelDefinition> models = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    /**
     * 缓存写入告警：模型 id -> 失败原因。
     *
     * <p>与 {@link #errors} 分开：缓存写失败不影响模型加载，
     * 混进去会让「模型列表」把好模型显示成失败。</p>
     */
    private final Map<String, String> cacheWarnings = new ConcurrentHashMap<>();
    private final ModelValidator validator;
    private final HelsteraEventBus eventBus;
    private final Path cacheDirectory;
    private final boolean cacheEnabled;
    /** 注册前的最后一步（用于销毁残留实例），可为 null。 */
    private volatile Consumer<String> onUnload;

    public ModelRegistryImpl(ModelValidator validator, HelsteraEventBus eventBus,
                             Path cacheDirectory, boolean cacheEnabled) {
        this.validator = validator;
        this.eventBus = eventBus;
        this.cacheDirectory = cacheDirectory;
        this.cacheEnabled = cacheEnabled;
    }

    public void setOnUnload(Consumer<String> onUnload) {
        this.onUnload = onUnload;
    }

    @Override
    public void register(ModelDefinition model) {
        if (models.containsKey(model.id())) {
            throw new IllegalStateException("重复模型 ID: " + model.id()
                    + "（当前来自 " + model.sourceDirectory() + "，请修改 manifest.yml 的 id）");
        }
        models.put(model.id(), model);
        // 错误键有两套命名：load() 用模型目录名，register() 侧用模型 id。
        // 只按 id 清理会留下按目录名登记的旧错误，导致同一目录在列表里既显示
        // 「已加载」又显示「失败」。两套键都清。
        errors.remove(model.id());
        Path src = model.sourceDirectory();
        if (src != null && src.getFileName() != null) {
            errors.remove(src.getFileName().toString());
        }
        eventBus.post(new ModelLoadedEvent(model, true, null));
    }

    @Override
    public CompletableFuture<Optional<ModelDefinition>> load(Path modelDirectory) {
        return load(modelDirectory, modelDirectory.getFileName() == null ? "?" : modelDirectory.getFileName().toString());
    }

    /**
     * 与 {@link #load(Path)} 相同，但用 {@code label}（通常是相对模型根的路径）作为错误键，
     * 便于网页开发器定位到具体目录。
     */
    public CompletableFuture<Optional<ModelDefinition>> load(Path modelDirectory, String label) {
        return CompletableFuture.supplyAsync(() -> {
            String key = "dir:" + label;
            try {
                ModelParser.ParseResult result = ModelParser.parse(modelDirectory);
                ModelDefinitionImpl model = result.model();
                List<String> errs = validator.validate(model);
                if (!errs.isEmpty()) {
                    errors.put(key, "校验失败: " + String.join("; ", errs));
                    return Optional.<ModelDefinition>empty();
                }
                if (cacheEnabled && cacheDirectory != null) writeCache(model);
                return Optional.<ModelDefinition>of(model);
            } catch (Exception e) {
                String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
                // 解析异常可能很长；只保留首行，避免刷屏
                int nl = msg.indexOf('\n');
                if (nl > 0) msg = msg.substring(0, nl);
                errors.put(key, msg);
                return Optional.<ModelDefinition>empty();
            }
        });
    }

    /** 清空历史错误（reload 前调用，避免已修复的模型仍显示旧错误）。 */
    public void clearErrors() {
        errors.clear();
    }

    private void writeCache(ModelDefinitionImpl model) {
        try {
            Files.createDirectories(cacheDirectory);
            String hash = hashDirectory(model.sourceDirectory());
            Path meta = cacheDirectory.resolve(model.id().replace('/', '_') + ".cache.json");
            String json = "{\"hash\":\"" + hash + "\",\"id\":\"" + model.id() + "\",\"format\":\"" + model.sourceFormat() + "\"}";
            Files.writeString(meta, json);
            cacheWarnings.remove(model.id());
        } catch (Exception e) {
            // 缓存写失败不影响功能（只是下次要重新解析），但绝不能静默：
            // 此前是 catch(Exception ignored){}，于是「缓存一直没生效」
            // 这件事没有任何排查入口，作者只能怀疑配置或版本。
            // 记进独立的 cacheWarnings 而非 errors —— 模型本身是好的，
            // 混进 errors 会让 /helstera model list 把它显示成加载失败。
            cacheWarnings.put(model.id(), e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private String hashDirectory(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var s = Files.walk(dir, 3)) {
                s.filter(Files::isRegularFile).sorted().forEach(f -> {
                    try {
                        md.update(f.getFileName().toString().getBytes());
                        md.update(Files.readAllBytes(f));
                    } catch (IOException ignored) {
                    }
                });
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // 此前所有失败都返回固定的 "unknown"，导致**所有**哈希失败的模型
            // 共享同一指纹：改了其中一个的模型资源，另一个仍被判定为「未变更」
            // 而命中旧缓存，且日志里没有任何痕迹。
            // 改为把目录路径混进结果：至少不同模型不会互相碰撞，
            // 且路径本身变化时指纹也会变。
            return "unhashed-" + Integer.toHexString(dir.toAbsolutePath().normalize().toString().hashCode());
        }
    }

    @Override
    public Optional<ModelDefinition> get(String modelId) {
        return Optional.ofNullable(models.get(modelId.toLowerCase(Locale.ROOT)));
    }

    @Override
    public boolean isLoaded(String modelId) {
        return models.containsKey(modelId.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<ModelDefinition> all() {
        return List.copyOf(models.values());
    }

    @Override
    public boolean unload(String modelId) {
        ModelDefinition removed = models.remove(modelId.toLowerCase(Locale.ROOT));
        if (removed == null) return false;
        if (onUnload != null) {
            try {
                onUnload.accept(removed.id());
            } catch (Throwable ignored) {
            }
        }
        eventBus.post(new ModelLoadedEvent(removed, false, null));
        return true;
    }

    @Override
    public int unloadAll() {
        int n = models.size();
        for (String id : new ArrayList<>(models.keySet())) unload(id);
        return n;
    }

    @Override
    public int count() {
        return models.size();
    }

    @Override
    public CompletableFuture<List<String>> validate(Path modelDirectory) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> out = new ArrayList<>();
            try {
                ModelParser.ParseResult result = ModelParser.parse(modelDirectory);
                out.addAll(validator.validate(result.model()));
                out.addAll(result.warnings());
            } catch (Exception e) {
                out.add("解析失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            return out;
        });
    }

    public Map<String, String> currentErrors() {
        return Map.copyOf(errors);
    }

    /** 缓存写入告警；正常情况下为空。供 /helstera model list 展示。 */
    public Map<String, String> currentCacheWarnings() {
        return Map.copyOf(cacheWarnings);
    }
}
