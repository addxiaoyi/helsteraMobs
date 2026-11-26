package dev.helstera.api.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Static native AddTrade fields. Mechanic aliases do not imply an operation. */
public record MerchantTradeSpec(String action, int slot, String ingredient, String secondIngredient, String result,
                                int maxUses, boolean experienceReward, int villagerExperience, float priceMultiplier,
                                int demand, int specialPrice, boolean ignoreDiscounts) {
    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "addtrade", "settrade", "removetrade", "replacetrade", "merchant-trade" -> "merchant-trade";
            case "opentrades", "opentrade", "trade", "open-trades" -> "open-trades";
            default -> "";
        };
    }
    public static boolean supports(String name) { return !canonical(name).isEmpty(); }
    public static MerchantTradeSpec parse(List<String> arguments) {
        Map<String,String> f = MythicGeneratedLootSpec.named(arguments);
        return new MerchantTradeSpec(first(f,"ADD","action","mode","m").toUpperCase(Locale.ROOT),
                integer(first(f,"0","slot","s","index"),0),
                first(f,"STONE","ingredient","item","ingredient1","item1","i","i1"),
                first(f,null,"ingredient2","item2","i2"), first(f,"STONE","result","r"),
                integer(first(f,Integer.toString(Integer.MAX_VALUE),"maxuses","uses","u"),Integer.MAX_VALUE),
                Boolean.parseBoolean(first(f,"false","experiencereward","expreward","exp","dropexp")),
                integer(first(f,"0","villagerexp","villexp","vexp"),0),
                floating(first(f,"0","pricemultiplier","multiplier")),
                integer(first(f,"1","demand","d"),1), integer(first(f,"1","specialprice","special"),1),
                Boolean.parseBoolean(first(f,"false","ignorediscounts","discounts")));
    }
    public static boolean realTrade(List<String> arguments) {
        return Boolean.parseBoolean(first(MythicGeneratedLootSpec.named(arguments),"true","realtrade","real"));
    }
    private static String first(Map<String,String> fields, String fallback, String... aliases) {
        return MythicGeneratedLootSpec.first(fields,fallback,aliases);
    }
    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static float floating(String value) {
        float number;
        try { number = Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return 0; }
        if (!Float.isFinite(number)) throw new IllegalArgumentException("Non-finite trade price multiplier");
        return number;
    }
}
