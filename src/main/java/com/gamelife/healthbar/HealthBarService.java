
package com.gamelife.healthbar;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class HealthBarService implements Listener {

    private final JavaPlugin plugin;

    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> lookTasks = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean mobs;
    private volatile boolean players;
    private volatile boolean look;

    private volatile long showMillis;
    private volatile double maxDistance;
    private volatile int frames = 10;
    private volatile boolean mythicPresent;

    private volatile Set<EntityType> blacklist =
            Set.of(EntityType.ARMOR_STAND);

    // Cấu hình độ cao và kích thước mặc định.
    private volatile double defaultHeightOffset = 0.35;
    private volatile float defaultScale = 1.0f;

    // Cấu hình riêng theo MythicMob internal name.
    private volatile Map<String, Double> mythicHeights = Map.of();
    private volatile Map<String, Float> mythicScales = Map.of();


    private static final class Bar {
        final TextDisplay display;
        final Map<UUID, Long> viewers = new ConcurrentHashMap<>();

        ScheduledTask task;
        String last = "";

        Bar(TextDisplay display) {
            this.display = display;
        }
    }

    HealthBarService(JavaPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attachLook);
        mythicPresent = Bukkit.getPluginManager().isPluginEnabled("MythicMobs");

    }

    void stop() {
        HandlerList.unregisterAll(this);

        lookTasks.values().forEach(ScheduledTask::cancel);
        lookTasks.clear();

        bars.keySet().forEach(this::remove);
    }

    void reloadSettings() {
        var c = plugin.getConfig().getConfigurationSection("mob-bars");
        frames = Math.max(1, Math.min(256, plugin.getConfig().getInt("bar.frames", 10)));
        enabled = c == null || c.getBoolean("enabled", true);
        mobs = c == null || c.getBoolean("mobs", true);
        players = c == null || c.getBoolean("players", true);
        look = c == null || c.getBoolean("look", true);

        showMillis = Math.max(
                1,
                (c == null ? 3 : c.getLong("show-seconds", 3))
        ) * 1000L;

        maxDistance = Math.max(
                1,
                c == null ? 32 : c.getDouble("max-distance", 32)
        );

        defaultHeightOffset = c == null
                ? 0.35
                : c.getDouble("default-height-offset", 0.35);

        defaultScale = (float) Math.max(
                0.1,
                c == null ? 1.0 : c.getDouble("default-scale", 1.0)
        );

        Set<EntityType> types = new HashSet<>();

        if (c != null) {
            for (String s : c.getStringList("blacklist")) {
                try {
                    types.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning(
                            "Unknown entity type in blacklist: " + s
                    );
                }
            }
        }

        blacklist = Set.copyOf(types);

        Map<String, Double> heights = new HashMap<>();
        Map<String, Float> scales = new HashMap<>();

        ConfigurationSection mythicSection =
                c == null ? null : c.getConfigurationSection("mythicmobs");

        ConfigurationSection settingsSection =
                mythicSection == null
                        ? null
                        : mythicSection.getConfigurationSection("settings");

        if (settingsSection != null) {
            for (String mobId : settingsSection.getKeys(false)) {
                ConfigurationSection mobSection =
                        settingsSection.getConfigurationSection(mobId);

                if (mobSection == null) {
                    continue;
                }

                if (mobSection.contains("height")) {
                    heights.put(
                            mobId,
                            mobSection.getDouble("height")
                    );
                }

                if (mobSection.contains("scale")) {
                    scales.put(
                            mobId,
                            (float) Math.max(
                                    0.1,
                                    mobSection.getDouble("scale")
                            )
                    );
                }
            }
        }

        mythicHeights = Map.copyOf(heights);
        mythicScales = Map.copyOf(scales);

        // Xóa thanh cũ để áp dụng cấu hình mới.
        bars.keySet().forEach(this::remove);

        Bukkit.getOnlinePlayers().forEach(this::attachLook);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        attachLook(e.getPlayer());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled
                || !(e.getEntity() instanceof LivingEntity target)
                || !allowed(target)) {
            return;
        }

        Player viewer = attacker(e);

        if (viewer == null || viewer.equals(target)) {
            return;
        }

        target.getScheduler().run(
                plugin,
                task -> show(target, viewer),
                null
        );
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScheduledTask task = lookTasks.remove(e.getPlayer().getUniqueId());

        if (task != null) {
            task.cancel();
        }

        remove(e.getPlayer().getUniqueId());
    }

    private Player attacker(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) {
            return p;
        }

        if (e.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }

        return null;
    }

    private boolean allowed(LivingEntity entity) {
        if (entity instanceof Player) {
            return players;
        }

        return mobs && !blacklist.contains(entity.getType());
    }

    private void attachLook(Player player) {
        ScheduledTask old = lookTasks.remove(player.getUniqueId());

        if (old != null) {
            old.cancel();
        }

        ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> {
                    if (!enabled || !look || !player.isOnline()) {
                        return;
                    }

                    var hit = player.getWorld().rayTraceEntities(
                            player.getEyeLocation(),
                            player.getEyeLocation().getDirection(),
                            maxDistance,
                            0.2,
                            entity -> entity instanceof LivingEntity
                                    && !entity.equals(player)
                    );

                    if (hit != null
                            && hit.getHitEntity() instanceof LivingEntity target
                            && allowed(target)) {
                        target.getScheduler().run(
                                plugin,
                                scheduled -> show(target, player),
                                null
                        );
                    }
                },
                () -> lookTasks.remove(player.getUniqueId()),
                1L,
                5L
        );

        if (task != null) {
            lookTasks.put(player.getUniqueId(), task);
        }
    }

    private void show(LivingEntity target, Player viewer) {
        if (!enabled || !target.isValid() || !viewer.isOnline()) {
            return;
        }

        Bar bar = bars.get(target.getUniqueId());

        if (bar == null) {
            bar = create(target);
            bars.put(target.getUniqueId(), bar);
        }

        bar.viewers.put(
                viewer.getUniqueId(),
                System.currentTimeMillis() + showMillis
        );

        Bar currentBar = bar;

        viewer.getScheduler().run(
                plugin,
                task -> viewer.showEntity(plugin, currentBar.display),
                null
        );
    }

    private Bar create(LivingEntity target) {
        double heightOffset = getHeightOffset(target);
        float scale = getBarScale(target);

        Location barLocation = target.getLocation()
                .clone()
                .add(0, target.getHeight() + heightOffset, 0);

        TextDisplay display = target.getWorld().spawn(
                barLocation,
                TextDisplay.class,
                textDisplay -> {
                    textDisplay.setVisibleByDefault(false);
                    textDisplay.setPersistent(false);
                    textDisplay.setBillboard(Display.Billboard.CENTER);
                    textDisplay.setDefaultBackground(false);
                    textDisplay.setBackgroundColor(
                            Color.fromARGB(0, 0, 0, 0)
                    );
                    textDisplay.setShadowed(true);
                    textDisplay.setLineWidth(1000);

                    textDisplay.setTransformation(
                            new Transformation(
                                    new Vector3f(0, 0, 0),
                                    new AxisAngle4f(),
                                    new Vector3f(scale, scale, scale),
                                    new AxisAngle4f()
                            )
                    );
                }
        );

        Bar bar = new Bar(display);

        bar.task = target.getScheduler().runAtFixedRate(
                plugin,
                task -> tick(target, bar),
                () -> remove(target.getUniqueId()),
                1L,
                2L
        );

        return bar;
    }


    private String getMythicMobId(LivingEntity entity) {
        if (!mythicPresent) return null;
        try { return MythicHook.idOf(entity); }
        catch (Throwable t) { mythicPresent = false; return null; }
    }


    private double getHeightOffset(LivingEntity entity) {
        String mobId = getMythicMobId(entity);

        if (mobId != null && mythicHeights.containsKey(mobId)) {
            return mythicHeights.get(mobId);
        }

        return defaultHeightOffset;
    }

    private float getBarScale(LivingEntity entity) {
        String mobId = getMythicMobId(entity);

        if (mobId != null && mythicScales.containsKey(mobId)) {
            return mythicScales.get(mobId);
        }

        return defaultScale;
    }

    @SuppressWarnings("deprecation")
    private void tick(LivingEntity target, Bar bar) {
        if (!enabled || !target.isValid() || target.getHealth() <= 0) {
            remove(target.getUniqueId());
            return;
        }

        long now = System.currentTimeMillis();

        bar.viewers.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }

            Player player = Bukkit.getPlayer(entry.getKey());

            if (player != null) {
                player.getScheduler().run(
                        plugin,
                        task -> player.hideEntity(plugin, bar.display),
                        null
                );
            }

            return true;
        });

        if (bar.viewers.isEmpty()) {
            remove(target.getUniqueId());
            return;
        }

        // Cập nhật vị trí và độ cao theo target.
        Location barLocation = target.getLocation()
                .clone()
                .add(0, target.getHeight() + getHeightOffset(target), 0);

        bar.display.getScheduler().run(
                plugin,
                task -> {
                    if (bar.display.isValid()) bar.display.teleportAsync(barLocation);
                },
                null
        );

        double maxHealth = target.getMaxHealth();

        double ratio = maxHealth > 0
                ? Math.max(0, Math.min(1, target.getHealth() / maxHealth))
                : 0;

        int frame = Math.max(
                0,
                Math.min(9, (int) Math.ceil(ratio * 10) - 1)
        );

        String key = "frame-" + frame;

        if (!key.equals(bar.last)) {
            bar.last = key;

            bar.display.text(
                    Component.text(
                            new String(Character.toChars(0xE000 + frame))
                    ).font(Key.key("gamelifehealthbar", "healthbar"))
            );
        }
    }

    private void remove(UUID id) {
        Bar bar = bars.remove(id);

        if (bar == null) {
            return;
        }

        if (bar.task != null) {
            bar.task.cancel();
        }

        try {
            bar.display.getScheduler().run(
                    plugin,
                    task -> bar.display.remove(),
                    null
            );
        } catch (RuntimeException ignored) {
            // Entity có thể đã bị xóa hoặc scheduler đã dừng.
        }
    }


}
