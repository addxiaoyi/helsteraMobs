package dev.helstera.web;

import dev.helstera.core.validate.ModelValidator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 网页开发器与插件内核之间的桥接。
 *
 * <p>由 helstera-plugin 实现，注入 {@link WebServerService}。
 * 只读方法（models/errors/stats）可被 HTTP 线程直接调用（底层均为并发安全容器）；
 * 会触碰服务端主线程的方法（{@link #reloadModels()}、{@link #buildPack()}、{@link #spawn}）
 * 由实现方自行调度到主线程，调用者无需关心线程。</p>
 */
public interface WebBridge {

    /** 插件版本号。 */
    String version();

    /** 插件数据目录（mobs/ 等所在）。 */
    Path dataFolder();

    /** 模型根目录（默认 plugins/helsteraMobs/models）。 */
    Path modelsRoot();

    /** 校验器（使用与内核一致的纹理限制）。 */
    ModelValidator validator();

    /** 加载失败/校验错误：{@code dir:相对路径 -> 错误信息}。 */
    Map<String, String> errors();

    /** 已发现的全部模型目录（相对模型根的路径，含加载失败的）。 */
    List<String> modelDirs();

    /** 已加载模型摘要（不含立方体明细，列表用）。 */
    List<Map<String, Object>> modelsSummary();

    /** 单个模型详情（含骨骼/立方体，供预览用）；未加载时返回 null。 */
    Map<String, Object> modelDetail(String modelId);

    /** 运行时统计（模型数/实例数/TPS 等）。 */
    Map<String, Object> runtimeStats();

    /** 在线玩家名（用于网页端选择生成目标）。 */
    List<String> players();

    /** 触发模型重载（内部异步）。 */
    boolean reloadModels();

    /** 构建资源包。 */
    boolean buildPack();

    /** 当前资源包 zip；未构建返回 null。 */
    Path packZip();

    /** 生成一个生物实例，返回提示文本。 */
    String spawn(String mobId, String playerName);

    /**
     * 当前 config.yml 的完整内容（已解析为嵌套 Map）。
     *
     * <p>供网页端渲染配置表单。返回副本，调用方修改不影响服务端。</p>
     */
    Map<String, Object> configSnapshot();

    /**
     * 写入配置并落盘。
     *
     * @param patch 要合并进现有配置的键值（嵌套结构）
     * @return null 表示成功；非 null 为面向用户的错误说明
     */
    String applyConfigPatch(Map<String, Object> patch);
}
