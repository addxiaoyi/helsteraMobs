package dev.helstera.integrations.mythicmobs;

import dev.helstera.runtime.instance.InstanceManagerImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 把 helstera 的模型能力注册成 MythicMobs 的 mechanic 与 condition。
 *
 * <p>覆盖的 mechanic：{@code modelspawn / modelremove / modelplay / modelstop / modelscale}；
 * 覆盖的 condition：{@code modelspawned / modelremoved / modelplaying}。
 * 这样 MythicMobs 的技能表里就能直接写 {@code - modelspawn example/emberling 1.2}，
 * 不再需要配合 {@code /helstera} 命令手动绑定。</p>
 *
 * <p>实现方式是 {@link Proxy} 而非编译期依赖：MythicMobs 的
 * {@code MythicCondition.isMet} / {@code Skill.castAt} 参数表在 5.3~5.7 之间多次变动，
 * 而 helstera 必须能在未安装 MythicMobs 的环境下编译。代理按<em>方法名</em>分派、
 * 按<em>类型</em>扫描参数（Entity / List&lt;String&gt; / String[]），因此签名微调不会导致
 * NoSuchMethodError，只会让对应 mechanic 静默不注册（并在适配器状态报告里写明原因）。</p>
 *
 * <p>线程约束：注册在插件启用期（主线程）；mechanic 执行由 MythicMobs 在主线程调用。</p>
 */
final class MythicMobsModelMechanics {

    private final InstanceManagerImpl instances;
    private final dev.helstera.api.model.ModelRegistry registry;
    private final Map<String, String> registered = new LinkedHashMap<>();
    private final Map<String, MechanicBody> bodies = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    MythicMobsModelMechanics(InstanceManagerImpl instances, dev.helstera.api.model.ModelRegistry registry) {
        this.instances = instances;
        this.registry = registry;
    }

    /** 已注册的键（mechanic 与 condition 混在一起，用于调试展示）。 */
    Map<String, String> registered() {
        return Map.copyOf(registered);
    }

    /** 注册失败原因；空表示全部成功。 */
    List<String> problems() {
        return List.copyOf(problems);
    }

    boolean anyRegistered() {
        return !registered.isEmpty();
    }

    /**
     * 向 MythicMobs 注册全部模型 mechanic 与 condition。
     *
     * @return 实际注册成功的条目数
     */
    int register(Plugin host) {
        Object skillManager = resolveSkillManager();
        if (skillManager == null) return 0;

        registerConditions(skillManager);
        registerMechanics(skillManager);
        return registered.size();
    }

    // ------------------------------------------------------------------
    // 反射解析
    // ------------------------------------------------------------------

    /**
     * 取 MythicLib 的 SkillManager。
     *
     * <p>不同版本拿法不同：先是 {@code MythicLib.inst().getSkillManager()}，
     * 退化到静态 {@code MythicLib.inst()}，再退化到 Bukkit 主插件上的同名方法。</p>
     */
    private Object resolveSkillManager() {
        try {
            Class<?> mythicLibClass = Class.forName("io.lumine.mythic.lib.MythicLib");
            Object inst = mythicLibClass.getMethod("inst").invoke(null);
            if (inst != null) {
                try {
                    Object sm = inst.getClass().getMethod("getSkillManager").invoke(inst);
                    if (sm != null) return sm;
                } catch (NoSuchMethodException ignored) {
                    // 旧版本字段直接暴露在 MythicLib 上
                }
                for (Method m : inst.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && "getSkillManager".equals(m.getName())) {
                        Object sm = m.invoke(inst);
                        if (sm != null) return sm;
                    }
                }
            }
            problems.add("MythicLib.inst() 未能取到 SkillManager");
            return null;
        } catch (ClassNotFoundException e) {
            problems.add("找不到 io.lumine.mythic.lib.MythicLib（MythicMobs 未安装或版本过旧）");
            return null;
        } catch (Throwable t) {
            problems.add("解析 MythicMobs SkillManager 失败: " + t);
            return null;
        }
    }

    private void registerConditions(Object skillManager) {
        put("modelspawned", "condition", "该生物是否已绑定 helstera 模型", (entity, args) -> {
            // isMet 的返回值由代理层按方法名回填，这里只需执行判定副作用：
            // 条件语义通过代理返回 true/false 传递，body 只负责求值。
            conditionResult.set(find(entity) != null);
        });
        put("modelremoved", "condition", "该生物是否已解绑 helstera 模型", (entity, args) -> {
            conditionResult.set(entity != null && !entity.isValid() && find(entity) == null);
        });
        put("modelplaying", "condition", "参数: <动画名>；该生物模型是否在播放该动画", (entity, args) -> {
            var inst = find(entity);
            if (inst == null) {
                conditionResult.set(false);
                return;
            }
            if (args.isEmpty()) {
                conditionResult.set(inst.animation().currentAnimation().isPresent());
                return;
            }
            conditionResult.set(inst.animation().currentAnimation().filter(args.get(0)::equals).isPresent());
        });
        for (String name : List.of("modelspawned", "modelremoved", "modelplaying")) {
            registerCondition(skillManager, name);
        }
    }

    private void registerMechanics(Object skillManager) {
        // modelspawn <modelId> [scale] [animation] [loop]
        put("modelspawn", "mechanic", "参数: <模型ID> [缩放] [动画] [循环]", (entity, args) -> {
            if (entity == null || args.isEmpty()) return;
            if (find(entity) != null) return; // 已绑定则不重复叠加
            String modelId = args.get(0);
            if (!registry.isLoaded(modelId)) return;
            double scale = num(args, 1, 1.0);
            String anim = args.size() > 2 && !args.get(2).isBlank() ? args.get(2) : null;
            boolean loop = args.size() > 3 && Boolean.parseBoolean(args.get(3));
            var opts = dev.helstera.api.instance.SpawnOptions.defaults().scale(scale);
            var inst = instances.bind(modelId, entity, opts);
            if (anim != null) {
                inst.animation().play(anim,
                        dev.helstera.api.animation.AnimationOptions.defaults().loop(loop));
            }
        });

        // modelremove
        put("modelremove", "mechanic", "解绑该生物的 helstera 模型", (entity, args) -> {
            var inst = find(entity);
            if (inst != null) instances.despawn(inst.instanceId());
        });

        // modelplay <animation> [loop] [priority]
        put("modelplay", "mechanic", "参数: <动画名> [循环] [优先级]", (entity, args) -> {
            var inst = find(entity);
            if (inst == null || args.isEmpty()) return;
            boolean loop = args.size() > 1 && Boolean.parseBoolean(args.get(1));
            int priority = (int) num(args, 2, 7);
            inst.animation().play(args.get(0),
                    dev.helstera.api.animation.AnimationOptions.defaults().loop(loop).priority(priority));
        });

        // modelstop [animation]
        put("modelstop", "mechanic", "参数: [动画名]；省略则停止全部", (entity, args) -> {
            var inst = find(entity);
            if (inst == null) return;
            if (args.isEmpty()) inst.animation().stopAll();
            else inst.animation().stop(args.get(0));
        });

        // modelscale <scale>
        put("modelscale", "mechanic", "参数: <缩放倍率>", (entity, args) -> {
            var inst = find(entity);
            if (inst == null || args.isEmpty()) return;
            inst.setScale(num(args, 0, 1.0));
        });

        // modelmount / modelunmount：把模型实例挂到生物身上跟随（视觉挂接）
        put("modelmount", "mechanic", "让模型跟随生物移动（默认绑定即为跟随，此处显式复位）", (entity, args) -> {
            var inst = find(entity);
            if (inst != null && entity != null) inst.lastLocationSet(entity.getLocation());
        });
        put("modelunmount", "mechanic", "解除跟随并解绑模型", (entity, args) -> {
            var inst = find(entity);
            if (inst != null) instances.despawn(inst.instanceId());
        });

        // modelheal：把模型血量上限同步到载体生物，模型血量归零时销毁实例
        put("modelheal", "mechanic", "把生物当前血量比例同步为模型缩放比例", (entity, args) -> {
            var inst = find(entity);
            if (inst == null || !(entity instanceof LivingEntity le) || le.getMaxHealth() <= 0) return;
            inst.setScale(Math.max(0.1, le.getHealth() / le.getMaxHealth()));
        });

        for (String name : List.of("modelspawn", "modelremove", "modelplay", "modelstop",
                "modelscale", "modelmount", "modelunmount", "modelheal")) {
            registerSkill(skillManager, name);
        }
    }

    // ------------------------------------------------------------------
    // 注册执行
    // ------------------------------------------------------------------

    /** mechanic / condition 的统一入口：Entity + 字符串参数 -> 执行动作。 */
    private interface MechanicBody {
        void run(Entity entity, List<String> args);
    }

    /**
     * 条件的求值出口。
     *
     * <p>条件与 mechanic 共用同一 {@link MechanicBody}（都只有 Entity + 参数），
     * 但 {@code isMet} 必须返回布尔值。body 把结论写进这里，代理层随即读取。
     * 用 ThreadLocal 而非普通字段：mechanic 在主线程顺序执行不会串值，
     * 而条件可能在其余线程被求值，普通字段会互相污染。</p>
     */
    private final ThreadLocal<Boolean> conditionResult = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private void put(String name, String kind, String description, MechanicBody body) {
        bodies.put(name, body);
        registered.put(name, kind + " — " + description);
    }

    private void registerCondition(Object skillManager, String name) {
        if (!bodies.containsKey(name)) return;
        try {
            Class<?> iface = Class.forName("io.lumine.mythic.core.skills.MythicCondition");
            Object proxy = newProxy(iface, name, bodies.get(name));
            invokeRegister(skillManager, "registerCondition", iface, name, proxy);
            Bukkit.getLogger().info("  MythicMobs 条件已注册: " + name);
        } catch (ClassNotFoundException e) {
            problems.add("找不到 MythicCondition 接口，条件 " + name + " 未注册");
        } catch (Throwable t) {
            problems.add("注册条件 " + name + " 失败: " + t);
        }
    }

    private void registerSkill(Object skillManager, String name) {
        if (!bodies.containsKey(name)) return;
        try {
            Class<?> iface = Class.forName("io.lumine.mythic.core.skills.Skill");
            Object proxy = newProxy(iface, name, bodies.get(name));
            invokeRegister(skillManager, "registerSkill", iface, name, proxy);
            Bukkit.getLogger().info("  MythicMobs mechanic 已注册: " + name);
        } catch (ClassNotFoundException e) {
            problems.add("找不到 Skill 接口，mechanic " + name + " 未注册");
        } catch (Throwable t) {
            problems.add("注册 mechanic " + name + " 失败: " + t);
        }
    }

    /** 按名字找 registerCondition / registerSkill 重载，避免参数类型顺序差异。 */
    private void invokeRegister(Object skillManager, String methodName, Class<?> iface,
                                String name, Object proxy) {
        for (Method m : skillManager.getClass().getMethods()) {
            if (!methodName.equals(m.getName()) || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (!p[0].isAssignableFrom(String.class) && p[0] != String.class) continue;
            if (!p[1].isInstance(proxy) && p[1] != Object.class) continue;
            try {
                m.invoke(skillManager, name, proxy);
                return;
            } catch (Throwable t) {
                throw new IllegalStateException(methodName + " 调用失败: " + t, t);
            }
        }
        // 该版本 SkillManager 上没有这个注册入口：作为失败原因上报，
        // 而不是抛受检异常——调用方统一 catch Throwable 记入 problems。
        throw new IllegalStateException("SkillManager 上找不到 " + methodName + "("
                + iface.getSimpleName() + ")");
    }

    /**
     * 构造 MythicMobs 接口的动态代理。
     *
     * <p>按方法名分派：{@code isMet} / {@code castAt} 执行模型动作，
     * {@code getName} 回显名字，{@code register} 返回自身，其余返回类型默认值。</p>
     */
    private Object newProxy(Class<?> iface, String name, MechanicBody body) {
        InvocationHandler h = (proxy, method, args) -> {
            String mn = method.getName();
            switch (mn) {
                case "isMet", "castAt", "execute" -> {
                    Entity entity = findEntity(args);
                    List<String> params = findStringList(args);
                    boolean wantsResult = "isMet".equals(mn)
                            && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
                    try {
                        if (wantsResult) conditionResult.set(Boolean.FALSE);
                        body.run(entity, params);
                        if (wantsResult) return conditionResult.get();
                        // mechanic（castAt）返回 void；个别版本声明成 boolean 时按 true 处理
                        return defaultFor(method.getReturnType(), true);
                    } catch (Throwable t) {
                        Bukkit.getLogger().warning("MythicMobs " + name + " 执行失败: " + t);
                        return defaultFor(method.getReturnType(), false);
                    }
                }
                case "getName" -> {
                    return name;
                }
                case "register" -> {
                    return proxy;
                }
                case "toString" -> {
                    return "helstera:" + name;
                }
                case "hashCode" -> {
                    return name.hashCode();
                }
                case "equals" -> {
                    return proxy == (args == null ? null : args[0]);
                }
                default -> {
                    return defaultFor(method.getReturnType(), false);
                }
            }
        };
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private static Object defaultFor(Class<?> type, boolean boolValue) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return boolValue;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == char.class || type == Character.class) return (char) 0;
        return null;
    }

    /** 在实参里找承载实体：优先取第二个 Entity（target 优先于 source）。 */
    private static Entity findEntity(Object[] args) {
        if (args == null) return null;
        List<Entity> found = new ArrayList<>(2);
        for (Object a : args) {
            if (a instanceof Entity e) found.add(e);
        }
        if (found.isEmpty()) return null;
        return found.size() >= 2 ? found.get(1) : found.get(0);
    }

    /** 在实参里找字符串参数：List&lt;String&gt; 或 varargs String[]。 */
    private static List<String> findStringList(Object[] args) {
        if (args == null) return List.of();
        for (Object a : args) {
            if (a instanceof String[] arr) return List.of(arr);
            if (a instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object o : list) out.add(String.valueOf(o));
                return out;
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 按绑定实体的 UUID 找实例；这就是 MythicMobs mechanic 与 helstera 实例之间的连接点。 */
    private dev.helstera.runtime.instance.ModelInstanceImpl find(Entity entity) {
        if (entity == null || instances == null) return null;
        UUID id = entity.getUniqueId();
        for (var inst : instances.allImpl()) {
            if (inst.boundEntityId().map(id::equals).orElse(false)) return inst;
        }
        return null;
    }

    private static double num(List<String> args, int i, double def) {
        if (args.size() <= i) return def;
        try {
            return Double.parseDouble(args.get(i).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}