package com.randomizechests;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class what Spigot instantiates when the server loads.
 * It handles startup (onEnable) and shutdown (onDisable).
 *
 * "extends JavaPlugin" means this IS a Bukkit plugin. Spigot finds it
 * because plugin.yml points to this class via the "main" field.
 */
public class RandomizeChests extends JavaPlugin {

    /**
     * Called by Spigot when the plugin is enabled (server startup or /reload).
     * We register our one command here.
     */
    @Override
    public void onEnable() {
        // Copy the default config.yml into the plugin's data folder
        // if it doesn't already exist. This lets ops edit probability
        // weights without touching the jar.
        saveDefaultConfig();

        // Wire up the /tierchests command to our TierCommand handler.
        // "tierchests" must match the command name in plugin.yml exactly.
        getCommand("tierchests").setExecutor(new TierCommand(this));

        getLogger().info("RandomizeChests enabled! Use /tierchests <arenaName> to randomize chest tiers.");
    }

    /**
     * Called by Spigot when the plugin is disabled (server shutdown or /reload).
     * Nothing to clean up for this plugin, but the method is here for completeness.
     */
    @Override
    public void onDisable() {
        getLogger().info("RandomizeChests disabled.");
    }
}
