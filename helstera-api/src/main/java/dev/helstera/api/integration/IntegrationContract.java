package dev.helstera.api.integration;

import java.util.Set;

/**
 * Static boundary declared by an optional external-plugin adapter.
 *
 * <p>{@link IntegrationAdapter#capabilities()} is the active runtime state;
 * this contract describes what the adapter is designed to provide and what it
 * requires before that state may become active.</p>
 */
public record IntegrationContract(
        String pluginName,
        String supportedVersions,
        Set<Capability> declaredCapabilities,
        Set<String> prerequisites,
        boolean failClosed,
        boolean preservesSourceValues) {

    public IntegrationContract {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("pluginName must not be blank");
        }
        if (supportedVersions == null || supportedVersions.isBlank()) {
            throw new IllegalArgumentException("supportedVersions must not be blank");
        }
        declaredCapabilities = declaredCapabilities == null
                ? Set.of() : Set.copyOf(declaredCapabilities);
        prerequisites = prerequisites == null ? Set.of() : Set.copyOf(prerequisites);
        if (prerequisites.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("prerequisites must not contain blank values");
        }
    }

    public boolean declares(Capability capability) {
        return capability != null && declaredCapabilities.contains(capability);
    }

    public static IntegrationContract blocked(String pluginName, String supportedVersions,
                                              String prerequisite) {
        return new IntegrationContract(pluginName, supportedVersions, Set.of(),
                prerequisite == null || prerequisite.isBlank() ? Set.of() : Set.of(prerequisite),
                true, true);
    }
}
