package com.quant.pricing.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortfolioExecutionSimulatorTest {

    @Test
    void shouldDemonstrateDiversificationBenefit() {
        // Arrange
        double[] initialPrices = {100.0, 100.0};
        
        // Liquidate 10,000 shares of each asset over 5 steps (linear TWAP)
        double[][] trajectories = {
            {10000.0, 8000.0, 6000.0, 4000.0, 2000.0, 0.0},
            {10000.0, 8000.0, 6000.0, 4000.0, 2000.0, 0.0}
        };

        int numSteps = 5;
        double[] etas = {1e-5, 1e-5};   // temp impact
        double[] gammas = {1e-6, 1e-6}; // perm impact
        double tau = 1.0;
        int numPaths = 2000;

        // Portfolio A: Positively Correlated (rho = 0.8)
        // Vol_1 = Vol_2 = 0.30 -> Var_1 = Var_2 = 0.09. Cov_12 = 0.8 * 0.3 * 0.3 = 0.072
        double[][] posCov = {
            {0.09, 0.072},
            {0.072, 0.09}
        };

        // Portfolio B: Negatively Correlated (rho = -0.8)
        // Cov_12 = -0.8 * 0.3 * 0.3 = -0.072
        double[][] negCov = {
            {0.09, -0.072},
            {-0.072, 0.09}
        };

        PortfolioExecutionSimulator simulator = new PortfolioExecutionSimulator();

        // When
        ExecutionResult posResult = simulator.simulate(initialPrices, trajectories, numSteps, posCov, etas, gammas, tau, numPaths);
        ExecutionResult negResult = simulator.simulate(initialPrices, trajectories, numSteps, negCov, etas, gammas, tau, numPaths);

        // Then
        assertNotNull(posResult);
        assertNotNull(negResult);

        System.out.printf("Positive Correlation Shortfall Std Dev: %.2f USD%n", posResult.shortfallStandardDeviation());
        System.out.printf("Negative Correlation Shortfall Std Dev: %.2f USD%n", negResult.shortfallStandardDeviation());

        // Under negative correlation, standard deviation of portfolio shortfall must be LOWER (diversification benefit)
        assertTrue(negResult.shortfallVariance() < posResult.shortfallVariance(), 
                "Negative correlation must provide a diversification benefit and lower shortfall variance");
    }
}
