package dev.helstera.api.model;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 模型注册表。注册/查询/卸载模型。
 * 线程约束：query 任意线程；load/unload 必须主线程。
 */
public interface ModelRegistry {

    /** 同步注册已解析模型（主线程）。 */
    void register(ModelDefinition model);

    /** 从模型目录异步解析并校验，完成后在主线程注册。 */
    CompletableFuture<Optional<ModelDefinition>> load(java.nio.file.Path modelDirectory);

    Optional<ModelDefinition> get(String modelId);

    boolean isLoaded(String modelId);

    Collection<ModelDefinition> all();

    /** 卸载模型；若存在实例将同时销毁它们。 */
    boolean unload(String modelId);

    /** 清空并重载全部（reload 用）。 */
    int unloadAll();

    int count();

    /** 校验模型目录，返回错误列表（异步）。 */
    CompletableFuture<java.util.List<String>> validate(java.nio.file.Path modelDirectory);
}
