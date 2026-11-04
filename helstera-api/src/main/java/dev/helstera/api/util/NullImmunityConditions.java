package dev.helstera.api.util;

import org.bukkit.configuration.ConfigurationSection;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Restores explicit null immunity fields discarded by Bukkit's YAML configuration. */
public final class NullImmunityConditions {
    private NullImmunityConditions() { }

    public static int restore(ConfigurationSection config, Path source) throws IOException {
        try (var reader = Files.newBufferedReader(source)) {
            return visit(new Yaml().compose(reader), config, "", "", false, 0);
        }
    }

    private static int visit(Node node, ConfigurationSection section,
                              String parent, String grandparent, boolean insideEntry, int depth) {
        if (!(node instanceof MappingNode mapping) || depth > 64) return 0;
        int restored = 0;
        for (var tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) continue;
            String key = keyNode.getValue();
            Node value = tuple.getValueNode();
            boolean immunity = grandparent.equalsIgnoreCase("immunities")
                    || grandparent.equalsIgnoreCase("damage-modifiers")
                    || grandparent.equalsIgnoreCase("DamageModifiers");
            boolean modifierEntry = parent.equalsIgnoreCase("damage-modifiers")
                    || parent.equalsIgnoreCase("DamageModifiers");
            boolean immunityEntry = parent.equalsIgnoreCase("immunities");
            if ((insideEntry || immunity || modifierEntry || immunityEntry)
                    && Tag.NULL.equals(value.getTag())) {
                section.set(key, ConditionListParser.INVALID_CONDITION);
                restored++;
            } else {
                ConfigurationSection child = section.getConfigurationSection(key);
                boolean nestedEntry = insideEntry || immunity;
                if (child == null && nestedEntry && value instanceof MappingNode && !section.contains(key)) {
                    child = section.createSection(key);
                }
                if (child != null) restored += visit(value, child, key, parent, nestedEntry, depth + 1);
            }
        }
        return restored;
    }
}
