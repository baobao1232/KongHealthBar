package com.gamelife.healthbar;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class HealthBarService implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> lookTasks = new ConcurrentHashMap<>();
    private volatile boolean enabled, mobs, players, look;
    private volatile long showMillis;
    private volatile double maxDistance;
    private volatile Set<EntityType> blacklist = Set.of(EntityType.ARMOR_STAND);
    private static final class Bar {
        final TextDisplay display;
        final Map<UUID, Long> viewers = new ConcurrentHashMap<>();
        ScheduledTask task;
        String last = "";
        Bar(TextDisplay display) { this.display = display; }
    }
    HealthBarService(JavaPlugin plugin) { this.plugin = plugin; reloadSettings(); }
    void start() { plugin.getServer().getPluginManager().registerEvents(this, plugin); Bukkit.getOnlinePlayers().forEach(this::attachLook); }
    void stop() { HandlerList.unregisterAll(this); lookTasks.values().forEach(ScheduledTask::cancel); lookTasks.clear(); bars.keySet().forEach(this::remove); }
    void reloadSettings() {
        var c = plugin.getConfig().getConfigurationSection("mob-bars");
        enabled = c == null || c.getBoolean("enabled", true); mobs = c == null || c.getBoolean("mobs", true); players = c == null || c.getBoolean("players", true);
        look = c == null || c.getBoolean("look", true); showMillis = Math.max(1, c == null ? 3 : c.getLong("show-seconds", 3)) * 1000L;
        maxDistance = Math.max(1, c == null ? 32 : c.getDouble("max-distance", 32));
        Set<EntityType> types = new HashSet<>();
        if (c != null) for (String s : c.getStringList("blacklist")) try { types.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) { plugin.getLogger().warning("Unknown entity type in blacklist: " + s); }
        blacklist = Set.copyOf(types); bars.keySet().forEach(this::remove);
        Bukkit.getOnlinePlayers().forEach(this::attachLook);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { attachLook(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled || !(e.getEntity() instanceof LivingEntity target) || !allowed(target)) return;
        Player viewer = attacker(e); if (viewer == null || viewer.equals(target) || viewer.getUniqueId().getMostSignificantBits() == 0) return;
        target.getScheduler().run(plugin, task -> show(target, viewer), null);
    }
    @EventHandler public void onDeath(EntityDeathEvent e) { remove(e.getEntity().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { ScheduledTask t = lookTasks.remove(e.getPlayer().getUniqueId()); if (t != null) t.cancel(); remove(e.getPlayer().getUniqueId()); }
    private Player attacker(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p) return p; if (e.getDamager() instanceof Projectile p && p.getShooter() instanceof Player s) return s; return null; }
    private boolean allowed(LivingEntity e) { return e instanceof Player ? players : mobs && !blacklist.contains(e.getType()); }
    private void attachLook(Player p) {
        ScheduledTask old = lookTasks.remove(p.getUniqueId()); if (old != null) old.cancel();
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, task -> {
            if (!enabled || !look || p.getUniqueId().getMostSignificantBits() == 0) return;
            var hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), maxDistance, .2, e -> e instanceof LivingEntity && !e.equals(p));
            if (hit != null && hit.getHitEntity() instanceof LivingEntity target && allowed(target)) target.getScheduler().run(plugin, x -> show(target, p), null);
        }, () -> lookTasks.remove(p.getUniqueId()), 1L, 5L);
        if (t != null) lookTasks.put(p.getUniqueId(), t);
    }
    private void show(LivingEntity target, Player viewer) {
        if (!enabled || !target.isValid() || !viewer.isOnline()) return;
        Bar b = bars.get(target.getUniqueId());
        if (b == null) { b = create(target); bars.put(target.getUniqueId(), b); }
        b.viewers.put(viewer.getUniqueId(), System.currentTimeMillis() + showMillis);
        Bar bar = b; viewer.getScheduler().run(plugin, task -> viewer.showEntity(plugin, bar.display), null);
    }
    private Bar create(LivingEntity target) {
        TextDisplay d = target.getWorld().spawn(target.getLocation(), TextDisplay.class, display -> {
            display.setVisibleByDefault(false); display.setPersistent(false); display.setBillboard(Display.Billboard.CENTER); display.setDefaultBackground(false); display.setBackgroundColor(Color.fromARGB(0,0,0,0)); display.setShadowed(true); display.setLineWidth(1000);
            display.setTransformation(new Transformation(new Vector3f(0, .35f, 0), new AxisAngle4f(), new Vector3f(1), new AxisAngle4f()));
        }); target.addPassenger(d);
        Bar b = new Bar(d); b.task = target.getScheduler().runAtFixedRate(plugin, task -> tick(target,b), () -> remove(target.getUniqueId()), 1L, 5L); return b;
    }
    @SuppressWarnings("deprecation") private void tick(LivingEntity target, Bar b) {
        if (!enabled || !target.isValid() || target.getHealth() <= 0) { remove(target.getUniqueId()); return; }
        long now = System.currentTimeMillis();
        b.viewers.entrySet().removeIf(entry -> { if (entry.getValue() > now) return false; Player p = Bukkit.getPlayer(entry.getKey()); if (p != null) p.getScheduler().run(plugin, t -> p.hideEntity(plugin,b.display), null); return true; });
        if (b.viewers.isEmpty()) { remove(target.getUniqueId()); return; }
        double ratio = target.getMaxHealth() > 0 ? Math.max(0, Math.min(1, target.getHealth() / target.getMaxHealth())) : 0;
        int frame = Math.max(0, Math.min(9, (int)Math.ceil(ratio * 10) - 1));
        String key = "frame-" + frame;
        if (!key.equals(b.last)) { b.last = key; b.display.text(Component.text(new String(Character.toChars(0xE000 + frame))).font(Key.key("gamelifehealthbar", "healthbar"))); }
    }
    private void remove(UUID id) { Bar b = bars.remove(id); if (b == null) return; if (b.task != null) b.task.cancel(); try { b.display.getScheduler().run(plugin, task -> b.display.remove(), null); } catch (RuntimeException ex) { b.display.remove(); } }
}
