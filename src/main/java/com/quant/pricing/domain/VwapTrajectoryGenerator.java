package com.quant.pricing.domain;

public class VwapTrajectoryGenerator {

    /**
     * Generates a VWAP target holding trajectory.
     * The liquidation schedule matches the provided historical volume profile fraction by fraction.
     *
     * @param totalShares initial share count to liquidate
     * @param volumeProfile array of size N where index k is the volume fraction traded at step k + 1
     * @return double[] target holdings trajectory of size N + 1
     */
    public double[] generate(double totalShares, double[] volumeProfile) {
        if (volumeProfile == null || volumeProfile.length == 0) {
            throw new IllegalArgumentException("Volume profile cannot be null or empty.");
        }

        // Validate that volume profile fractions sum to 1.0 (with precision tolerance)
        double sum = 0.0;
        for (double fraction : volumeProfile) {
            if (fraction < 0.0 || fraction > 1.0) {
                throw new IllegalArgumentException("Volume fractions must be between 0.0 and 1.0.");
            }
            sum += fraction;
        }

        if (Math.abs(sum - 1.0) > 1e-5) {
            throw new IllegalArgumentException("Volume profile fractions must sum to exactly 1.0. Current sum: " + sum);
        }

        int numSteps = volumeProfile.length;
        double[] trajectory = new double[numSteps + 1];
        trajectory[0] = totalShares;

        for (int k = 1; k <= numSteps; k++) {
            double slice = totalShares * volumeProfile[k - 1];
            trajectory[k] = trajectory[k - 1] - slice;
        }

        // Clean up small rounding errors on final step boundary
        trajectory[numSteps] = 0.0;

        return trajectory;
    }
}
