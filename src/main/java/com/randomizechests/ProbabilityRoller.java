package com.randomizechests;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Random;

/**
 * Handles the math for choosing a chest tier based on distance.
 *
 * The idea: we have three anchor points (center, midrange, edge) each with
 * probability weights for tier 1/2/3. For any given chest, we interpolate
 * between anchors based on its normalized distance "t", then roll a random
 * number to pick the tier.
 *
 * Two-segment linear interpolation:
 *   t in [0.0, 0.5] → blend between center and midrange anchors
 *   t in (0.5, 1.0] → blend between midrange and edge anchors
 */
public class ProbabilityRoller {

    // Probability weights at the three anchor points.
    // Each array is {tier1Weight, tier2Weight, tier3Weight}.
    private final double[] centerWeights;
    private final double[] midrangeWeights;
    private final double[] edgeWeights;

    private final Random random = new Random();

    /**
     * Creates a ProbabilityRoller by reading weights from the plugin config.
     *
     * @param config the plugin's config.yml, already loaded by Bukkit
     */
    public ProbabilityRoller(FileConfiguration config) {
        // Read the three anchor points from config.yml
        // Each is an array of {tier1, tier2, tier3} weights
        centerWeights = new double[]{
            config.getDouble("probabilities.center.tier1", 85),
            config.getDouble("probabilities.center.tier2", 14),
            config.getDouble("probabilities.center.tier3", 1)
        };
        midrangeWeights = new double[]{
            config.getDouble("probabilities.midrange.tier1", 60),
            config.getDouble("probabilities.midrange.tier2", 25),
            config.getDouble("probabilities.midrange.tier3", 15)
        };
        edgeWeights = new double[]{
            config.getDouble("probabilities.edge.tier1", 10),
            config.getDouble("probabilities.edge.tier2", 50),
            config.getDouble("probabilities.edge.tier3", 40)
        };
    }

    /**
     * Rolls a random tier (1, 2, or 3) for a chest at normalized distance t.
     *
     * @param t normalized distance from center: 0.0 = center, 1.0 = edge.
     *          Clamped to [0.0, 1.0] for safety.
     * @return 1 (CHEST / weakest), 2 (TRAPPED_CHEST / mid), or 3 (BARREL / best)
     */
    public int rollTier(double t) {
        t = Math.max(0.0, Math.min(1.0, t));

        // Interpolate weights for this specific distance
        double[] weights = interpolateWeights(t);

        // Normalize so weights sum to exactly 100.
        // They *should* already sum to 100 if config is correct, but this
        // guarantees it even if someone puts in wonky numbers.
        double total = weights[0] + weights[1] + weights[2];
        if (total <= 0) {
            // Degenerate config default to tier 1
            return 1;
        }
        weights[0] = (weights[0] / total) * 100.0;
        weights[1] = (weights[1] / total) * 100.0;
        weights[2] = (weights[2] / total) * 100.0;

        // Roll a random number from 0 to 99.999...
        double roll = random.nextDouble() * 100.0;

        // Check which tier the roll falls into
        if (roll < weights[0]) {
            return 1; // Tier 1: CHEST (weakest)
        } else if (roll < weights[0] + weights[1]) {
            return 2; // Tier 2: TRAPPED_CHEST (mid)
        } else {
            return 3; // Tier 3: BARREL (best)
        }
    }

    /**
     * Two-segment linear interpolation between the three anchor points.
     *
     * If t is in [0.0, 0.5]:
     *   blend factor f = t * 2  (so f goes from 0.0 at center to 1.0 at midrange)
     *   weight = center + f * (midrange - center)
     *
     * If t is in (0.5, 1.0]:
     *   blend factor f = (t - 0.5) * 2  (0.0 at midrange to 1.0 at edge)
     *   weight = midrange + f * (edge - midrange)
     *
     * @param t normalized distance [0.0, 1.0]
     * @return interpolated {tier1Weight, tier2Weight, tier3Weight}
     */
    private double[] interpolateWeights(double t) {
        double[] result = new double[3];

        if (t <= 0.5) {
            // Blending between center and midrange
            double f = t * 2.0; // 0.0 at center, 1.0 at midrange
            for (int i = 0; i < 3; i++) {
                result[i] = centerWeights[i] + f * (midrangeWeights[i] - centerWeights[i]);
            }
        } else {
            // Blending between midrange and edge
            double f = (t - 0.5) * 2.0; // 0.0 at midrange, 1.0 at edge
            for (int i = 0; i < 3; i++) {
                result[i] = midrangeWeights[i] + f * (edgeWeights[i] - midrangeWeights[i]);
            }
        }

        return result;
    }
}
