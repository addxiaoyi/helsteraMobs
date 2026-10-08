package dev.helstera.ai.dialog;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 对话服务（dialogs.yml）。
 *
 * <p>每个实例同时只运行一条 cinematic，按 tick 推进。
 * 同一实例多次触发会打断当前 cinematic，从头开始新的一条——
 * 这样「玩家靠近时重新开始对话」这类场景不会卡在中间步骤。</p>
 *
 * <p>线程约束：装载在主线程；步进器在 tick 回调中运行，也在主线程。</p>
 */
public final class DialogueService {

    /** 实例 ID -> 正在运行的 cinematic 步进器。 */
    private final Map<Integer, Runner> active = new ConcurrentHashMap<>();
    private final Map<String, Dialogs.DialogueEntry> dialogues = new ConcurrentHashMap<>();
    private final Logger log;
    /** 实例查找函数：由插件层注入，用于在 cinematic 步进时查找实例。 */
    private final Function<Integer, ModelInstance> instanceResolver;

    public DialogueService(Logger log, Function<Integer, ModelInstance> instanceResolver) {
        this.log = log;
        this.instanceResolver = instanceResolver;
    }

    /** 从 {@code dialogs.yml} 的 {@code dialogues} 节装载对话定义。 */
    public void load(ConfigurationSection sec, List<String> problems) {
        dialogues.clear();
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection entrySec = sec.getConfigurationSection(name);
            if (entrySec == null) continue;
            List<String> entryProblems = new ArrayList<>();
            Dialogs.DialogueEntry entry = parseEntry(name, entrySec, entryProblems);
            if (entry == null) {
                entryProblems.forEach(problems::add);
                continue;
            }
            dialogues.put(entry.name(), entry);
            entryProblems.forEach(problems::add);
        }
        if (log != null) log.info("[对话] 装载 " + dialogues.size() + " 条对话");
    }

    /** 已装载的对话名列表；供诊断命令使用。 */
    public List<String> dialogueNames() {
        return Collections.unmodifiableList(new ArrayList<>(dialogues.keySet()));
    }

    /**
     * 为指定实例播放对话。打断当前进行中的 cinematic，从头开始。
     *
     * @param instId 实例 ID
     * @param name   对话名（对应 dialogs.yml 的顶层 key）
     * @return 是否成功开始；对话未找到时返回 false
     */
    public boolean start(int instId, String name, org.bukkit.plugin.java.JavaPlugin plugin) {
        Dialogs.DialogueEntry entry = dialogues.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) return false;
        Runner old = active.remove(instId);
        if (old != null) old.cancel();
        Runner runner = new Runner(instId, entry.cinematic(), 0);
        active.put(instId, runner);
        runner.start(plugin);
        return true;
    }

    /** 停止指定实例的 cinematic（调试用）。 */
    public void stop(int instId) {
        Runner r = active.remove(instId);
        if (r != null) r.cancel();
    }

    /** 是否有实例正在播放对话。 */
    public boolean isActive(int instId) {
        return active.containsKey(instId);
    }

    /** 当前活跃的 cinematic 数。 */
    public int activeCount() {
        return active.size();
    }

    private static Dialogs.DialogueEntry parseEntry(
            String name, ConfigurationSection sec, List<String> problems) {
        List<Dialogs.CinematicStep> steps = new ArrayList<>();
        ConfigurationSection cinemas = sec.getConfigurationSection("cinematics");
        if (cinemas == null) {
            problems.add("对话 \"" + name + "\" 缺少 cinematics 节");
            return null;
        }
        for (String cinName : cinemas.getKeys(false)) {
            ConfigurationSection cinSec = cinemas.getConfigurationSection(cinName);
            if (cinSec == null) continue;
            List<Dialogs.CinematicStep> cinSteps = Dialogs.parseCinematic(cinSec, problems);
            if (!cinSteps.isEmpty()) steps.addAll(cinSteps);
        }
        if (steps.isEmpty()) {
            problems.add("对话 \"" + name + "\" 的 cinematics 解析后为空");
            return null;
        }
        return Dialogs.DialogueEntry.of(name, steps);
    }

    /** 单实例的 cinematic 步进器，每 tick 推进一步。 */
    private final class Runner {
        private final int instId;
        private final List<Dialogs.CinematicStep> steps;
        private int stepIndex;
        private int waitRemaining;
        private BukkitTask task;
        private org.bukkit.plugin.java.JavaPlugin pluginRef;

        Runner(int instId, List<Dialogs.CinematicStep> steps, int startStep) {
            this.instId = instId;
            this.steps = steps;
            this.stepIndex = startStep;
            this.waitRemaining = 0;
        }

        void start(org.bukkit.plugin.java.JavaPlugin plugin) {
            this.pluginRef = plugin;
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
        }

        void cancel() {
            if (task != null) {
                try { task.cancel(); } catch (Exception e) {
                    // 取消失败不影响对话结束，但静默会让「对话结束后任务还在跑」
                    // 这种问题无从查证。只吞 Exception，Error 不吞。
                    if (log != null) log.warning("[对话] 取消定时任务失败（对话仍正常结束）: " + e.getMessage());
                }
                task = null;
            }
        }

        private void tick() {
            if (stepIndex >= steps.size()) {
                cancel();
                active.remove(instId);
                return;
            }
            Dialogs.CinematicStep step = steps.get(stepIndex);
            if (step.isWait()) {
                int ticks = Integer.parseInt(step.args().get(0));
                if (waitRemaining <= 0) waitRemaining = ticks;
                if (waitRemaining > 0) {
                    waitRemaining--;
                    return;
                }
                stepIndex++;
                tick();
                return;
            }
            execute(step);
            stepIndex++;
        }

        private void execute(Dialogs.CinematicStep step) {
            ModelInstance inst = instanceResolver.apply(instId);
            if (inst == null) { cancel(); active.remove(instId); return; }
            Location loc = inst.location();
            if (loc == null) { cancel(); active.remove(instId); return; }
            switch (step.type().toLowerCase(Locale.ROOT)) {
                case "say" -> {
                    String text = String.join(" ", step.args());
                    if (text.isBlank()) break;
                    for (Player p : loc.getWorld().getPlayers()) {
                        if (p.getLocation().distance(loc) <= 16.0) {
                            p.sendMessage(text);
                        }
                    }
                }
                case "move" -> {
                    if (step.args().size() < 4) break;
                    try {
                        double dx = Double.parseDouble(step.args().get(0));
                        double dy = Double.parseDouble(step.args().get(1));
                        double dz = Double.parseDouble(step.args().get(2));
                        double speed = Double.parseDouble(step.args().get(3));
                        Location target = loc.clone().add(dx, dy, dz);
                        inst.teleport(target);
                        inst.baseEntity().ifPresent(e -> e.teleport(target));
                        // move 是单步瞬移，速度参数此前被解析出来后直接丢弃。
                        // 作者写 speed: 5 会以为能控制移动快慢，实际毫无作用——
                        // 这是「配置写了没效果且无任何提示」的典型症状。
                        // 真正要控制节奏应该用 wait 步骤。
                        if (speed > 0 && log != null) {
                            log.warning("[对话] move 步骤忽略了 speed=" + speed
                                    + "（move 是瞬移）。要控制节奏请在 move 之后加 wait 步骤。");
                        }
                    } catch (NumberFormatException e) {
                        if (log != null) log.warning("[对话] move 参数解析失败: " + e.getMessage());
                    }
                }
                case "look" -> {
                    if (step.args().size() < 2) break;
                    try {
                        float yaw = Float.parseFloat(step.args().get(0));
                        float pitch = Float.parseFloat(step.args().get(1));
                        Location l = loc.clone();
                        l.setYaw(yaw);
                        l.setPitch(pitch);
                        inst.teleport(l);
                        inst.baseEntity().ifPresent(e -> e.teleport(l));
                    } catch (NumberFormatException e) {
                        if (log != null) log.warning("[对话] look 参数解析失败: " + e.getMessage());
                    }
                }
                default -> {
                    if (log != null) log.warning("[对话] 未知步进动作: " + step.type());
                }
            }
        }
    }
}