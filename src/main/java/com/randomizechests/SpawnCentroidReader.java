package com.randomizechests;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Reads HungerGames' setspawn.yml to find the cornucopia center.
 *
 * The spawn points form a ring around the cornucopia, so the average
 * of all their X and Z coordinates gives us the center. If setspawn.yml
 * is missing or empty, we fall back to the midpoint of the arena
 * bounding box from arena.yml.
 */
public class SpawnCentroidReader {

    /**
     * Computes the cornucopia center for a given arena.
     *
     * @param arenaFolder the arena's folder inside plugins/HungerGames/
     * @return a double[] with {centerX, centerZ}, or null if we can't determine it
     */
    public static double[] getCenter(File arenaFolder) {
        // Try the primary method: centroid of spawn points
        double[] centroid = readSpawnCentroid(arenaFolder);
        if (centroid != null) {
            return centroid;
        }

        // Fallback: midpoint of the arena bounding box
        return readArenaMidpoint(arenaFolder);
    }

    /**
     * Reads setspawn.yml and averages all spawn point X and Z coordinates.
     *
     * Each entry looks like: "worldName,x,y,z"
     * We split on commas and grab index 1 (x) and 3 (z).
     *
     * @return {avgX, avgZ} or null if the file is missing/empty/malformed
     */
    private static double[] readSpawnCentroid(File arenaFolder) {
        File spawnFile = new File(arenaFolder, "setspawn.yml");
        if (!spawnFile.exists()) {
            return null;
        }

        // YamlConfiguration is Bukkit's built-in YAML parser
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(spawnFile);
        List<String> spawnpoints = yaml.getStringList("spawnpoints");

        if (spawnpoints == null || spawnpoints.isEmpty()) {
            return null;
        }

        double sumX = 0;
        double sumZ = 0;
        int count = 0;

        for (String entry : spawnpoints) {
            // Split "worldName,x,y,z" into parts
            String[] parts = entry.split(",");
            if (parts.length < 4) {
                continue;
            }

            try {
                sumX += Double.parseDouble(parts[1].trim());
                sumZ += Double.parseDouble(parts[3].trim());
                count++;
            } catch (NumberFormatException e) {
                continue;
            }
        }

        if (count == 0) {
            return null;
        }

        // Return the average X and Z
        return new double[]{ sumX / count, sumZ / count };
    }

    /**
     * Reads arena.yml and computes the midpoint of pos1 and pos2.
     * This is the fallback when setspawn.yml isn't available.
     *
     * @return {midX, midZ} or null if arena.yml is missing/malformed
     */
    private static double[] readArenaMidpoint(File arenaFolder) {
        File arenaFile = new File(arenaFolder, "arena.yml");
        if (!arenaFile.exists()) {
            return null;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(arenaFile);

        // Check that both pos1 and pos2 exist in the file
        if (!yaml.contains("region.pos1") || !yaml.contains("region.pos2")) {
            return null;
        }

        double x1 = yaml.getDouble("region.pos1.x");
        double z1 = yaml.getDouble("region.pos1.z");
        double x2 = yaml.getDouble("region.pos2.x");
        double z2 = yaml.getDouble("region.pos2.z");

        // Midpoint formula: average of the two corners
        return new double[]{ (x1 + x2) / 2.0, (z1 + z2) / 2.0 };
    }

    /**
     * Reads the arena bounding box and computes the half-diagonal distance.
     * This is used to normalize chest distances to a 0.0–1.0 range.
     *
     * Half-diagonal = distance from center to corner of the bounding box.
     *
     * @param arenaFolder the arena's folder
     * @return the half-diagonal distance, or -1 if arena.yml is missing
     */
    public static double getHalfDiagonal(File arenaFolder) {
        File arenaFile = new File(arenaFolder, "arena.yml");
        if (!arenaFile.exists()) {
            return -1;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(arenaFile);

        if (!yaml.contains("region.pos1") || !yaml.contains("region.pos2")) {
            return -1;
        }

        double x1 = yaml.getDouble("region.pos1.x");
        double z1 = yaml.getDouble("region.pos1.z");
        double x2 = yaml.getDouble("region.pos2.x");
        double z2 = yaml.getDouble("region.pos2.z");

        // Full diagonal across the bounding box, then halved
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz) / 2.0;
    }
}
