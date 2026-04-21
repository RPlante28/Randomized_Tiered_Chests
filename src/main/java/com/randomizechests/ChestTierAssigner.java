package com.randomizechests;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.session.SessionManager;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Core logic class. Given an arena name, this:
 *   1. Pools all chest entries from chest-locations.yml (all three sections)
 *   2. Computes each chest's normalized distance from the center
 *   3. Rolls a weighted random tier for each chest
 *   4. Replaces the physical block in the world via WorldEdit
 *   5. Rewrites chest-locations.yml with entries sorted into the correct sections
 *
 * Returns a summary string so the command can report what happened.
 */
public class ChestTierAssigner {

    private final RandomizeChests plugin;
    private final Logger logger;

    public ChestTierAssigner(RandomizeChests plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Represents a single chest entry from chest-locations.yml.
     * We store all the fields so we can write them back exactly.
     */
    private static class ChestEntry {
        String world;
        double x, y, z;
        double pitch, yaw;

        ChestEntry(String world, double x, double y, double z, double pitch, double yaw) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        /**
         * Converts this entry back into a Map that YamlConfiguration can write.
         * This must match the format HungerGames plugin expects.
         */
        Map<String, Object> toMap() {
            // LinkedHashMap would preserve order, but HashMap is fine —
            // YAML doesn't guarantee key order anyway.
            Map<String, Object> map = new HashMap<>();
            map.put("world", world);
            map.put("x", x);
            map.put("y", y);
            map.put("z", z);
            map.put("pitch", pitch);
            map.put("yaw", yaw);
            return map;
        }
    }

    /**
     * Runs the full tier assignment process for one arena.
     *
     * @param arenaName the arena folder name
     * @param player    the player who ran the command (used for WorldEdit undo attribution)
     * @return a summary message string, or an error message starting with "Error:"
     */
    public String assignTiers(String arenaName, Player player) {
        // Locate the arena folder
        File hgFolder = new File(plugin.getDataFolder().getParentFile(), "HungerGames");
        File arenaFolder = new File(hgFolder, arenaName);

        if (!arenaFolder.exists() || !arenaFolder.isDirectory()) {
            return "Error: Arena folder not found: plugins/HungerGames/" + arenaName;
        }

        // Check required files
        File chestFile = new File(arenaFolder, "chest-locations.yml");
        if (!chestFile.exists()) {
            return "Error: No chest-locations.yml found for arena '" + arenaName
                + "'. Chests haven't been marked yet.";
        }

        File arenaYml = new File(arenaFolder, "arena.yml");
        if (!arenaYml.exists()) {
            return "Error: No arena.yml found for arena '" + arenaName + "'.";
        }

        // Find the center
        double[] center = SpawnCentroidReader.getCenter(arenaFolder);
        if (center == null) {
            return "Error: Could not determine center for arena '" + arenaName
                + "'. Neither setspawn.yml nor arena.yml had valid data.";
        }
        double centerX = center[0];
        double centerZ = center[1];
        logger.info("Arena '" + arenaName + "' center: X=" + centerX + " Z=" + centerZ);

        // Get the half-diagonal for distance normalization
        double halfDiagonal = SpawnCentroidReader.getHalfDiagonal(arenaFolder);
        if (halfDiagonal <= 0) {
            return "Error: Could not compute arena bounds for '" + arenaName + "'.";
        }
        logger.info("Arena '" + arenaName + "' half-diagonal: " + halfDiagonal);

        // Pool ALL chest entries from all three sections
        YamlConfiguration chestYaml = YamlConfiguration.loadConfiguration(chestFile);
        List<ChestEntry> allChests = new ArrayList<>();

        // Read each of the three sections and pool them together
        poolChestSection(chestYaml, "chest-locations", allChests);
        poolChestSection(chestYaml, "trapped-chests-locations", allChests);
        poolChestSection(chestYaml, "barrel-locations", allChests);

        if (allChests.isEmpty()) {
            return "Error: No chest entries found in chest-locations.yml for arena '" + arenaName + "'.";
        }

        logger.info("Pooled " + allChests.size() + " total chests from all sections.");

        // Determine the world for WorldEdit
        // All chests in an arena should be in the same world.
        // We use the world name from the first chest entry.
        String worldName = allChests.get(0).world;
        World bukkitWorld = Bukkit.getWorld(worldName);
        if (bukkitWorld == null) {
            return "Error: World '" + worldName + "' is not loaded. "
                + "Make sure the arena world is loaded before running this command.";
        }

        // Convert Bukkit world to WorldEdit world
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);

        // Create the probability roller from config
        ProbabilityRoller roller = new ProbabilityRoller(plugin.getConfig());

        // Output list
        List<ChestEntry> tier1List = new ArrayList<>(); // CHEST (weakest)
        List<ChestEntry> tier2List = new ArrayList<>(); // TRAPPED_CHEST (mid)
        List<ChestEntry> tier3List = new ArrayList<>(); // BARREL (best)

        // Open a WorldEdit EditSession and process each chest
        // Use need the WorldEdit actor and LocalSession to register undo history
        com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
        SessionManager sessionManager = WorldEdit.getInstance().getSessionManager();
        LocalSession localSession = sessionManager.get(wePlayer);

        EditSession editSession = null;
        try {
            editSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .actor(wePlayer)
                .build();

            for (ChestEntry chest : allChests) {
                // Calculate horizontal distance from center (ignore Y)
                double dx = chest.x - centerX;
                double dz = chest.z - centerZ;
                double distance = Math.sqrt(dx * dx + dz * dz);

                // Normalize to 0.0–1.0 range using half-diagonal
                double t = Math.min(distance / halfDiagonal, 1.0);

                // Roll a tier for this chest
                int tier = roller.rollTier(t);

                // Replace the block in the world and sort into the right list
                BlockVector3 pos = BlockVector3.at((int) chest.x, (int) chest.y, (int) chest.z);

                // Read the existing block to preserve its facing direction.
                // Chests, trapped chests, and barrels all have a "facing" property
                // (north, south, east, west). Without this, all new blocks would
                // face north (the default), which looks wrong.
                BlockState existingBlock = editSession.getBlock(pos);
                Property<?> facingProperty = existingBlock.getBlockType().getPropertyMap().get("facing");

                // Get the facing value from the old block (e.g., "north", "south", etc.)
                Object facingValue = null;
                if (facingProperty != null) {
                    facingValue = existingBlock.getState(facingProperty);
                }

                // Pick the new block type based on tier
                BlockState newBlock;
                switch (tier) {
                    case 2:
                        // Tier 2 = TRAPPED_CHEST (mid loot)
                        newBlock = BlockTypes.TRAPPED_CHEST.getDefaultState();
                        tier2List.add(chest);
                        break;
                    case 3:
                        // Tier 3 = BARREL (best loot)
                        newBlock = BlockTypes.BARREL.getDefaultState();
                        tier3List.add(chest);
                        break;
                    default:
                        // Tier 1 = regular CHEST (weakest loot)
                        newBlock = BlockTypes.CHEST.getDefaultState();
                        tier1List.add(chest);
                        break;
                }

                // Apply the original facing direction to the new block
                if (facingValue != null) {
                    Property<?> newFacingProperty = newBlock.getBlockType().getPropertyMap().get("facing");
                    if (newFacingProperty != null) {
                        @SuppressWarnings("unchecked")
                        Property<Object> castProperty = (Property<Object>) newFacingProperty;
                        newBlock = newBlock.with(castProperty, facingValue);
                    }
                }

                editSession.setBlock(pos, newBlock);
            }

        } catch (Exception e) {
            logger.severe("WorldEdit error while processing arena '" + arenaName + "': " + e.getMessage());
            e.printStackTrace();
            return "Error: WorldEdit failed while placing blocks. Check console for details.";
        } finally {
            if (editSession != null) {
                editSession.close();
            }
        }

        // Register the closed EditSession in the player's undo history.
        localSession.remember(editSession);

        // Rewrite chest-locations.yml
        try {
            rewriteChestLocations(chestFile, tier1List, tier2List, tier3List);
        } catch (IOException e) {
            logger.severe("Failed to write chest-locations.yml for '" + arenaName + "': " + e.getMessage());
            return "Error: Blocks were placed, but chest-locations.yml could not be saved. "
                + "The yml and world are now out of sync! Check console for details.";
        }

        // Return summary
        return "Done! Tiered " + allChests.size() + " chests in " + arenaName + ": "
            + tier1List.size() + " tier 1 / "
            + tier2List.size() + " tier 2 / "
            + tier3List.size() + " tier 3. "
            + "Use //undo to reverse block changes.";
    }

    /**
     * Reads one section of chest-locations.yml and adds all its entries
     * to the pooled list. If the section doesn't exist, this does nothing.
     *
     * @param yaml        the loaded YAML file
     * @param sectionName the section key (e.g., "chest-locations")
     * @param pool        the list to add entries to
     */
    private void poolChestSection(YamlConfiguration yaml, String sectionName, List<ChestEntry> pool) {
        // getMapList returns a List<Map<String, Object>>
        List<Map<?, ?>> entries = yaml.getMapList(sectionName);

        for (Map<?, ?> entry : entries) {
            try {
                String world = String.valueOf(entry.get("world"));
                double x = toDouble(entry.get("x"));
                double y = toDouble(entry.get("y"));
                double z = toDouble(entry.get("z"));
                double pitch = entry.containsKey("pitch") ? toDouble(entry.get("pitch")) : 0.0;
                double yaw = entry.containsKey("yaw") ? toDouble(entry.get("yaw")) : 0.0;

                pool.add(new ChestEntry(world, x, y, z, pitch, yaw));
            } catch (Exception e) {
                // Log and skip malformed entries rather than crashing
                logger.warning("Skipping malformed chest entry in section '" + sectionName + "': " + entry);
            }
        }
    }

    /**
     * Helper to safely convert a YAML value to double.
     * YAML sometimes loads numbers as Integer, sometimes as Double.
     */
    private double toDouble(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        return Double.parseDouble(String.valueOf(obj));
    }

    /**
     * Writes the three tier lists back to chest-locations.yml in the format
     * HungerGames expects.
     *
     * @param chestFile the chest-locations.yml file to overwrite
     * @param tier1     entries assigned to tier 1 (CHEST)
     * @param tier2     entries assigned to tier 2 (TRAPPED_CHEST)
     * @param tier3     entries assigned to tier 3 (BARREL)
     */
    private void rewriteChestLocations(File chestFile,
                                        List<ChestEntry> tier1,
                                        List<ChestEntry> tier2,
                                        List<ChestEntry> tier3) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();

        // Convert each list of ChestEntry objects into a list of maps
        yaml.set("chest-locations", toMapList(tier1));
        yaml.set("trapped-chests-locations", toMapList(tier2));
        yaml.set("barrel-locations", toMapList(tier3));

        yaml.save(chestFile);
    }

    /**
     * Converts a list of ChestEntry objects into a list of Maps
     * suitable for YAML serialization.
     */
    private List<Map<String, Object>> toMapList(List<ChestEntry> entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChestEntry entry : entries) {
            list.add(entry.toMap());
        }
        return list;
    }
}
