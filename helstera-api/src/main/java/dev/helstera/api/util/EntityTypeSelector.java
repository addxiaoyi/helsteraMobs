package dev.helstera.api.util;

import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Horse;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Llama;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.util.OldEnum;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Matches entity types with the per-type traits exposed by the Bukkit API. */
public final class EntityTypeSelector {
    private record Constraint(String trait, List<String> values) {}

    private record Entry(EntityType type, List<Constraint> constraints) {}

    private final List<Entry> entries;

    private EntityTypeSelector(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Parses comma-separated entity types with optional nested trait constraints. */
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
                        + (entry.constraints().isEmpty() ? "" : "[" + entry.constraints().stream()
                                .map(constraint -> constraint.trait() + "="
                                        + String.join(",", constraint.values()))
                                .reduce((left, right) -> left + ";" + right).orElse("") + "]"))
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
            try {
                if (entry.constraints().stream().allMatch(constraint -> matches(entity, constraint))) return true;
            } catch (RuntimeException ignored) {
                // A missing or unavailable Bukkit value fails this entry closed.
            }
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

        List<Constraint> constraints = new ArrayList<>();
        Set<String> seenTraits = new HashSet<>();
        for (String option : options) {
            int equals = option.indexOf('=');
            if (equals <= 0) return null;
            String trait = normalizeTrait(option.substring(0, equals));
            if (trait == null || !seenTraits.add(trait)) return null;

            List<String> rawValues = splitTopLevel(option.substring(equals + 1), ',');
            if (rawValues == null || rawValues.isEmpty()) return null;
            List<String> values = new ArrayList<>();
            for (String rawValue : rawValues) {
                String normalized = normalizeTraitValue(rawValue);
                if (normalized == null || !hasKnownTraitValue(type, trait, normalized)) return null;
                if (!values.contains(normalized)) values.add(normalized);
            }
            constraints.add(new Constraint(trait, List.copyOf(values)));
        }
        return new Entry(type, List.copyOf(constraints));
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

    private static String normalizeTrait(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().replaceAll("[\\s_-]+", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "variant", "v" -> "variant";
            case "color", "c" -> "color";
            case "style", "s" -> "style";
            case "maingene", "main", "mg" -> "mainGene";
            case "hiddengene", "hidden", "hg" -> "hiddenGene";
            case "biome", "b" -> "biome";
            case "profession", "prof", "proffesion" -> "profession";
            case "crack", "cracks", "crackedness", "crackiness" -> "crack";
            case "bodycolor", "body", "bc" -> "bodyColor";
            case "patterncolor", "pc" -> "patternColor";
            case "pattern", "p" -> "pattern";
            default -> null;
        };
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

    private static String normalizeTraitValue(String raw) {
        String normalized = normalizeName(raw);
        if (normalized == null) return null;
        if (normalized.startsWith("MINECRAFT:")) normalized = normalized.substring("MINECRAFT:".length());
        return normalized.contains(":") ? null : normalized;
    }

    private static boolean hasKnownTraitValue(EntityType entityType, String trait, String wanted) {
        if (hasDocumentedButUnavailableTrait(entityType, trait)) return false;
        if ("crack".equals(trait) && entityType == EntityType.IRON_GOLEM) {
            return Set.of("NONE", "LOW", "MEDIUM", "HIGH").contains(wanted);
        }
        Class<?> specificType = traitValueType(entityType, trait);
        if (specificType != null) {
            return ("color".equals(trait) && entityType == EntityType.SHEEP && "RAINBOW".equals(wanted))
                    || hasNamedConstant(specificType, wanted);
        }
        if ("color".equals(trait) && "RAINBOW".equals(wanted)) return true;
        return traitValueTypes(trait).stream().anyMatch(type -> hasNamedConstant(type, wanted));
    }

    private static boolean hasDocumentedButUnavailableTrait(EntityType entityType, String trait) {
        return "variant".equals(trait)
                && (entityType == EntityType.COW || entityType == EntityType.CHICKEN || entityType == EntityType.PIG);
    }

    private static Class<?> traitValueType(EntityType entityType, String trait) {
        if ("variant".equals(trait)) {
            return switch (entityType) {
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
        }
        return switch (trait) {
            case "color" -> switch (entityType) {
                case HORSE -> Horse.Color.class;
                case SHEEP, SHULKER -> DyeColor.class;
                case PARROT -> Parrot.Variant.class;
                case LLAMA, TRADER_LLAMA -> Llama.Color.class;
                default -> null;
            };
            case "style" -> entityType == EntityType.HORSE ? Horse.Style.class : null;
            case "mainGene", "hiddenGene" -> entityType == EntityType.PANDA ? Panda.Gene.class : null;
            case "biome" -> entityType == EntityType.VILLAGER || entityType == EntityType.ZOMBIE_VILLAGER
                    ? Villager.Type.class : null;
            case "profession" -> entityType == EntityType.VILLAGER || entityType == EntityType.ZOMBIE_VILLAGER
                    ? Villager.Profession.class : null;
            case "bodyColor", "patternColor" -> entityType == EntityType.TROPICAL_FISH ? DyeColor.class : null;
            case "pattern" -> entityType == EntityType.TROPICAL_FISH ? TropicalFish.Pattern.class : null;
            default -> null;
        };
    }

    private static List<Class<?>> traitValueTypes(String trait) {
        return switch (trait) {
            case "variant" -> List.of(Axolotl.Variant.class, Cat.Type.class, Fox.Type.class, Frog.Variant.class,
                    MushroomCow.Variant.class, Rabbit.Type.class, Villager.Type.class, Wolf.Variant.class);
            case "color" -> List.of(Horse.Color.class, DyeColor.class, Parrot.Variant.class, Llama.Color.class);
            case "style" -> List.of(Horse.Style.class);
            case "mainGene", "hiddenGene" -> List.of(Panda.Gene.class);
            case "biome" -> List.of(Villager.Type.class);
            case "profession" -> List.of(Villager.Profession.class);
            case "bodyColor", "patternColor" -> List.of(DyeColor.class);
            case "pattern" -> List.of(TropicalFish.Pattern.class);
            default -> List.of();
        };
    }

    private static boolean hasNamedConstant(Class<?> type, String wanted) {
        try {
            Field field = type.getField(wanted);
            return Modifier.isStatic(field.getModifiers()) && field.getType() == type;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }

    private static boolean matches(Entity entity, Constraint constraint) {
        if ("color".equals(constraint.trait()) && entity instanceof Sheep sheep
                && "jeb_".equals(sheep.getCustomName())) {
            return constraint.values().contains("RAINBOW");
        }
        String actual = readTrait(entity, constraint.trait());
        return actual != null && constraint.values().contains(actual);
    }

    private static String readTrait(Entity entity, String trait) {
        return switch (trait) {
            case "variant" -> variantName(entity);
            case "color" -> colorName(entity);
            case "style" -> entity instanceof Horse value ? valueName(value.getStyle()) : null;
            case "mainGene" -> entity instanceof Panda value ? valueName(value.getMainGene()) : null;
            case "hiddenGene" -> entity instanceof Panda value ? valueName(value.getHiddenGene()) : null;
            case "biome" -> biomeName(entity);
            case "profession" -> professionName(entity);
            case "crack" -> crackName(entity);
            case "bodyColor" -> entity instanceof TropicalFish value ? valueName(value.getBodyColor()) : null;
            case "patternColor" -> entity instanceof TropicalFish value ? valueName(value.getPatternColor()) : null;
            case "pattern" -> entity instanceof TropicalFish value ? valueName(value.getPattern()) : null;
            default -> null;
        };
    }

    private static String variantName(Entity entity) {
        if (entity instanceof Axolotl value) return valueName(value.getVariant());
        if (entity instanceof Cat value) return valueName(value.getCatType());
        if (entity instanceof Fox value) return valueName(value.getFoxType());
        if (entity instanceof Frog value) return valueName(value.getVariant());
        if (entity instanceof MushroomCow value) return valueName(value.getVariant());
        if (entity instanceof Rabbit value) return valueName(value.getRabbitType());
        if (entity instanceof Villager value) return valueName(value.getVillagerType());
        if (entity instanceof Wolf value) return valueName(value.getVariant());
        if (entity instanceof ZombieVillager value) return valueName(value.getVillagerType());
        return null;
    }

    private static String colorName(Entity entity) {
        if (entity instanceof Horse value) return valueName(value.getColor());
        if (entity instanceof Sheep value) return valueName(value.getColor());
        if (entity instanceof Shulker value) return valueName(value.getColor());
        if (entity instanceof Parrot value) return valueName(value.getVariant());
        if (entity instanceof Llama value) return valueName(value.getColor());
        return null;
    }

    private static String biomeName(Entity entity) {
        if (entity instanceof Villager value) return valueName(value.getVillagerType());
        if (entity instanceof ZombieVillager value) return valueName(value.getVillagerType());
        return null;
    }

    private static String professionName(Entity entity) {
        if (entity instanceof Villager value) return valueName(value.getProfession());
        if (entity instanceof ZombieVillager value) return valueName(value.getVillagerProfession());
        return null;
    }

    private static String crackName(Entity entity) {
        if (!(entity instanceof IronGolem value)) return null;
        AttributeInstance maxHealth = value.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth == null) return null;
        double maximum = maxHealth.getValue();
        double health = value.getHealth();
        if (!Double.isFinite(maximum) || maximum <= 0 || !Double.isFinite(health)) return null;
        double fraction = health / maximum;
        if (fraction >= 0.75) return "NONE";
        if (fraction >= 0.50) return "LOW";
        if (fraction >= 0.25) return "MEDIUM";
        return "HIGH";
    }

    private static String valueName(Object value) {
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof Keyed keyed && keyed.getKey() != null) {
            return keyed.getKey().getKey().toUpperCase(Locale.ROOT);
        }
        if (value instanceof OldEnum<?> oldEnum) return oldEnum.name();
        return null;
    }
}
