package dev.helstera.api;

/**
 * API 静态入口。引擎启动后可通过 {@link #get()} 获取。
 */
public final class HelsteraApi {

    private static volatile Helstera instance;

    private HelsteraApi() {
    }

    /** 引擎未启动时抛出 IllegalStateException。 */
    public static Helstera get() {
        Helstera h = instance;
        if (h == null) {
            throw new IllegalStateException("helsteraMobs 尚未启动，无法访问 API");
        }
        return h;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    /** 仅由引擎内部调用。 */
    public static void register(Helstera impl) {
        instance = impl;
    }

    public static void unregister() {
        instance = null;
    }
}
