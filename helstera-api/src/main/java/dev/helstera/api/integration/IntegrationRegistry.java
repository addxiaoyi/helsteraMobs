package dev.helstera.api.integration;

import java.util.Collection;
import java.util.Optional;
import org.bukkit.Location;

/**
 * 集成注册表：注册和查询 MythicMobs、ItemAdder、CraftEngine 等适配器。
 */
public interface IntegrationRegistry {

    void register(IntegrationAdapter adapter);

    Optional<IntegrationAdapter> get(String pluginName);

    Collection<IntegrationAdapter> all();

    /** 启用全部适配器（软依赖，失败隔离）。返回启用成功的数量。 */
    int enableAll(org.bukkit.plugin.Plugin host);

    /** 关闭全部适配器。 */
    void disableAll();

    /** 生成缺失依赖报告。 */
    String dependencyReport();

    default Boolean containsRegion(String region, Location location) {
        for (IntegrationAdapter adapter : all()) {
            if (!adapter.hasCapability(Capability.REGION_QUERY)) continue;
            Boolean answer = adapter.containsRegion(region, location);
            if (answer != null) return answer;
        }
        return null;
    }

    /** Returns true if any active claim provider contains the location. */
    default Boolean inClaim(Location location) {
        if (location == null || location.getWorld() == null) return null;
        boolean known = false;
        boolean unknown = false;
        for (IntegrationAdapter adapter : all()) {
            if (!adapter.hasCapability(Capability.CLAIM_QUERY)) continue;
            try {
                Boolean answer = adapter.inClaim(location);
                if (Boolean.TRUE.equals(answer)) return true;
                if (Boolean.FALSE.equals(answer)) known = true;
                else if (answer == null) unknown = true;
            } catch (RuntimeException ignored) {
                // A failing optional provider cannot turn an unknown answer into a match.
                unknown = true;
            }
        }
        return known && !unknown ? false : null;
    }

    /** Returns true if any active claim provider has a claim within the radius. */
    default Boolean nearClaim(Location location, double radius) {
        if (location == null || location.getWorld() == null
                || !Double.isFinite(radius) || radius < 0) return null;
        boolean known = false;
        boolean unknown = false;
        for (IntegrationAdapter adapter : all()) {
            if (!adapter.hasCapability(Capability.CLAIM_QUERY)) continue;
            try {
                Boolean answer = adapter.nearClaim(location, radius);
                if (Boolean.TRUE.equals(answer)) return true;
                if (Boolean.FALSE.equals(answer)) known = true;
                else if (answer == null) unknown = true;
            } catch (RuntimeException ignored) {
                // A failing optional provider cannot turn an unknown answer into a match.
                unknown = true;
            }
        }
        return known && !unknown ? false : null;
    }
}
