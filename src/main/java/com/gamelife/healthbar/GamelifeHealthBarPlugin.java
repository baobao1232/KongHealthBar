
package com.gamelife.healthbar;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class GamelifeHealthBarPlugin extends JavaPlugin {

    private HealthBarService healthBars;
    private ResourcePackManager packs;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        healthBars = new HealthBarService(this);
        healthBars.start();

        packs = new ResourcePackManager(this);
        packs.start();

        getLogger().info("GamelifeHealthBar enabled (standalone).");
    }

    @Override
    public void onDisable() {
        if (healthBars != null) {
            healthBars.stop();
        }

        if (packs != null) {
            packs.stop();
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            healthBars.reloadSettings();
            packs.reloadAndSend();
            sender.sendMessage("§a[GamelifeHealthBar] Đã tải lại cấu hình.");
            return true;
        }

        sender.sendMessage("§e/ghb reload");
        return true;
    }
}
