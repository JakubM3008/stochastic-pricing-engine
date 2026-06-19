package com.quant.pricing.domain;

public class AlmgrenChrissOptimizer {

    /**
     * Calculates the optimal holding trajectory x_j (shares remaining at step j)
     * using the discrete Almgren-Chriss framework with linear market impact.
     *
     * @param totalShares X_0, initial share position to liquidate
     * @param numSteps N, number of discrete trading intervals
     * @param volatility sigma, annual volatility of the asset
     * @param lambda risk aversion coefficient (higher = liquidate faster)
     * @param eta temporary market impact parameter
     * @param gamma permanent market impact parameter
     * @param tau duration of each time step (typically 1.0)
     * @return double[] array of size N + 1 where index j is the target holding x_j
     */
    public double[] optimize(double totalShares, int numSteps, double volatility, double lambda, double eta, double gamma, double tau) {
        double[] trajectory = new double[numSteps + 1];
        trajectory[0] = totalShares;
        trajectory[numSteps] = 0.0;

        // Case 1: Risk-neutral investor (lambda = 0) -> Linear liquidation (TWAP)
        if (lambda <= 0.0) {
            for (int j = 1; j < numSteps; j++) {
                trajectory[j] = totalShares * (1.0 - (double) j / numSteps);
            }
            return trajectory;
        }

        // Case 2: Risk-averse investor -> Solve difference equation
        // phi is the risk-to-impact ratio
        double phi = (lambda * volatility * volatility * tau) / eta;
        
        // Calculate discrete trading speed kappa*tau using acosh
        double z = 1.0 + phi / 2.0;
        double kappaTau = Math.log(z + Math.sqrt(z * z - 1.0));

        double sinhKappaN = Math.sinh(kappaTau * numSteps);

        for (int j = 1; j < numSteps; j++) {
            double sinhKappaNj = Math.sinh(kappaTau * (numSteps - j));
            trajectory[j] = totalShares * (sinhKappaNj / sinhKappaN);
        }

        return trajectory;
    }
}
