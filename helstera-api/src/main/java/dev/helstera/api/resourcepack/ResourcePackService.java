package dev.helstera.api.resourcepack;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * 资源包服务：构建、合并、哈希和下载状态。
 */
public interface ResourcePackService {

    /** 异步构建/合并资源包，返回 SHA-1 哈希（hex）。 */
    java.util.concurrent.CompletableFuture<String> build();

    /** 当前资源包哈希（未构建为 null）。 */
    String currentHash();

    /** 资源包下载地址（配置项 pack.url）。 */
    String url();

    /** 向玩家下发资源包。 */
    void apply(Player player);

    /** 向全体在线玩家下发。 */
    void applyAll();

    /** 玩家资源包状态：SUCCESSFULLY_LOADED / DECLINED / FAILED_DOWNLOAD / UNKNOWN。 */
    String statusOf(UUID playerId);

    /** 本地资源包文件路径。 */
    java.nio.file.Path packFile();
}
