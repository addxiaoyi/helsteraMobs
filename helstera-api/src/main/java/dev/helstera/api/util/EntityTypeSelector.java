package dev.helstera.api.util;

import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.Keyed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Matches entity types with the variant constraints exposed by the Bukkit API. */
public final class EntityTypeSelector {
    private record Entry(EntityType type, List<String> variants) {}

    private final List<Entry> entries;

    private EntityTypeSelector(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Parses comma-separated type names with optional {@code variant} constraints. */
    public static Optional<EntityTypeSelector> parse(String source) {
        if (source == null || source.isBlank()) return Optional.empty();
        List<String> rawEntries = splitTopLevel(source.trim(), ',');
        if (rawEntries == null || rawEntries.isEmpty()) return Optional.empty();

        List<Entry> entries = new ArrayList<>();
        for (String rawEntry : rawEntries) {
            Entry entry = parseEntry(rawEntry);
            if (entry == null) return Optional.empty();
            entries.add(entry);
        }
        return Optional.of(new EntityTypeSelector(entries));
    }

    /** Returns the normalized selector syntax accepted by {@link #parse(String)}. */
    public String canonical() {
        return entries.stream().map(entry -> entry.type().name()
                        + (entry.variants().isEmpty() ? "" : "[variant=" + String.join(",", entry.variants()) + "]"))
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    public boolean matches(Entity entity) {
        if (entity == null) return false;
        EntityType actualType;
        try {
            actualType = entity.getType();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (actualType == null) return false;
        for (Entry entry : entries) {
            if (entry.type() != actualType) continue;
            if (entry.variants().isEmpty()) return true;
            String actualVariant;
            try {
                actualVariant = variantName(entity);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (actualVariant != null && entry.variants().contains(actualVariant)) return true;
        }
        return false;
    }

    private static Entry parseEntry(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return null;
        int open = firstConstraintOpen(value);
        String typeName = open < 0 ? value : value.substring(0, open).trim();
        EntityType type = parseType(typeName);
        if (type == null) return null;
        if (open < 0) return new Entry(type, List.of());

        char opener = value.charAt(open);
        char closer = opener == '{' ? '}' : ']';
        if (value.charAt(value.length() - 1) != closer) return null;
        String body = value.substring(open + 1, value.length() - 1);
        List<String> options = splitTopLevel(body, ';');
        if (options == null || options.isEmpty()) return null;

        List<String> variants = null;
        for (String option : options) {
            int equals = option.indexOf('=');
            if (equals <= 0) return null;
            String key = option.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!key.equals("variant") && !key.equals("v") || variants != null) return null;

            List<String> values = splitTopLevel(option.substring(equals + 1), ',');
            if (values == null || values.isEmpty()) return null;
            variants = new ArrayList<>();
            for (String rawVariant : values) {
                String variant = normalizeVariantName(rawVariant);
                if (variant == null || !hasVariant(type, variant)) return null;
                if (!variants.contains(variant)) variants.add(variant);
            }
        }
        return new Entry(type, variants == null ? List.of() : List.copyOf(variants));
    }

    private static EntityType parseType(String raw) {
        String normalized = normalizeName(raw);
        if (normalized == null) return null;
        if (normalized.startsWith("MINECRAFT:")) normalized = normalized.substring("MINECRAFT:".length());
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().replaceAll("[\\s-]+", "_").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_:]*") ? normalized : null;
    }

    private static int firstConstraintOpen(String value) {
        int square = value.indexOf('[');
        int brace = value.indexOf('{');
        if (square < 0) return brace;
        if (brace < 0) return square;
        return Math.min(square, brace);
    }

    private static List<String> splitTopLevel(String source, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        ArrayDeque<Character> nesting = new ArrayDeque<>();
        for (int i = 0; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character == '{' || character == '[') {
                nesting.push(character);
            } else if (character == '}' || character == ']') {
                if (nesting.isEmpty()) return null;
                char expected = character == '}' ? '{' : '[';
                if (nesting.pop() != expected) return null;
            }
            if (character == delimiter && nesting.isEmpty()) {
                if (current.toString().isBlank()) return null;
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (!nesting.isEmpty() || current.toString().isBlank()) return null;
        result.add(current.toString().trim());
        return result;
    }

    private static boolean hasVariant(EntityType type, String wanted) {
        Class<?> variants = switch (type) {
            case AXOLOTL -> Axolotl.Variant.class;
            case CAT -> Cat.Type.class;
            case FOX -> Fox.Type.class;
            case FROG -> Frog.Variant.class;
            case MOOSHROOM -> MushroomCow.Variant.class;
            case RABBIT -> Rabbit.Type.class;
            case VILLAGER, ZOMBIE_VILLAGER -> Villager.Type.class;
            case WOLF -> Wolf.Variant.class;
            default -> null;
        };
        if (variants == null) return false;
        try {
            Field field = variants.getField(wanted);
            return Modifier.isStatic(field.getModifiers()) && field.getType() == variants;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }

    private static String normalizeVariantName(String raw) {
        String normalized = normalizeName(raw);
        if (normalized == null) return null;
        if (normalized.startsWith("MINECRAFT:")) return normalized.substring("MINECRAFT:".length());
        return normalized.contains(":") ? null : normalized;
    }

    private static String variantName(Entity entity) {
        if (entity instanceof Axolotl value) return variantName(value.getVariant());
        if (entity instanceof Cat value) return variantName(value.getCatType());
        if (entity instanceof Fox value) return variantName(value.getFoxType());
        if (entity instanceof Frog value) return variantName(value.getVariant());
        if (entity instanceof MushroomCow value) return variantName(value.getVariant());
        if (entity instanceof Rabbit value) return variantName(value.getRabbitType());
        if (entity instanceof Villager value) return variantName(value.getVillagerType());
        if (entity instanceof Wolf value) return variantName(value.getVariant());
        if (entity instanceof ZombieVillager value) return variantName(value.getVillagerType());
        return null;
    }

    private static String variantName(Object value) {
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof Keyed keyed && keyed.getKey() != null) {
            return keyed.getKey().getKey().toUpperCase(Locale.ROOT);
        }
        if (value instanceof org.bukkit.util.OldEnum<?> oldEnum) return oldEnum.name();
        return null;
    }
}
