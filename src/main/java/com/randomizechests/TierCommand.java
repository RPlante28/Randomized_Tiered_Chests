package com.randomizechests;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles the /tierchests command.
 *
 * Usage: /tierchests <arenaName>
 *
 * This validates the input, checks that the sender is a player (needed for
 * WorldEdit undo attribution), and then delegates to ChestTierAssigner
 * for the actual work.
 */
public class TierCommand implements CommandExecutor {

    private final RandomizeChests plugin;

    public TierCommand(RandomizeChests plugin) {
        this.plugin = plugin;
    }

    /**
     * Called by Bukkit when someone runs /tierchests.
     *
     * @param sender who ran the command (could be a player or console)
     * @param command the command object (we only have one, so we ignore this)
     * @param label   the exact alias used (e.g., "tierchests")
     * @param args    everything after the command name, split by spaces
     * @return true if the command was handled (even if it failed),
     *         false to show the usage message from plugin.yml
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // This command must be run by a player, not from console.
        // WorldEdit needs a player actor to attribute the EditSession to,
        // so that //undo works for the right person.
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command must be run by a player (not console).");
            return true;
        }

        // Check that an arena name was provided
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /tierchests <arenaName>");
            sender.sendMessage(ChatColor.GRAY + "Example: /tierchests ruins-of-altrac");
            return true;
        }

        // The arena name is the first argument
        String arenaName = args[0];

        sender.sendMessage(ChatColor.YELLOW + "[RandomizeChests] Processing arena '" + arenaName + "'...");

        // Do the actual work
        ChestTierAssigner assigner = new ChestTierAssigner(plugin);
        String result = assigner.assignTiers(arenaName, (Player) sender);

        // Color the result based on whether it's an error or success
        if (result.startsWith("Error:")) {
            sender.sendMessage(ChatColor.RED + "[RandomizeChests] " + result);
        } else {
            sender.sendMessage(ChatColor.GREEN + "[RandomizeChests] " + result);
        }

        return true;
    }
}
