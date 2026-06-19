package com.quant.pricing.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlmgrenChrissOptimizerTest {

    @Test
    void shouldGenerateLinearTrajectoryWhenRiskNeutral() {
        // Arrange
        double totalShares = 100000.0;
        int numSteps = 5;
        double volatility = 0.30;
        double lambda = 0.0; // Risk neutral
        double eta = 1e-5;   // Temp impact
        double gamma = 1e-6; // Perm impact
        double tau = 1.0;

        AlmgrenChrissOptimizer optimizer = new AlmgrenChrissOptimizer();

        // When
        double[] trajectory = optimizer.optimize(totalShares, numSteps, volatility, lambda, eta, gamma, tau);

        // Then: Trajectory should be linear (TWAP)
        // x_0 = 100,000
        // x_1 = 80,000
        // x_2 = 60,000
        // x_3 = 40,000
        // x_4 = 20,000
        // x_5 = 0
        assertEquals(totalShares, trajectory[0], 1e-9);
        assertEquals(80000.0, trajectory[1], 1e-2);
        assertEquals(60000.0, trajectory[2], 1e-2);
        assertEquals(40000.0, trajectory[3], 1e-2);
        assertEquals(20000.0, trajectory[4], 1e-2);
        assertEquals(0.0, trajectory[numSteps], 1e-9);
    }

    @Test
    void shouldSellFasterWhenRiskAverse() {
        // Arrange
        double totalShares = 100000.0;
        int numSteps = 5;
        double volatility = 0.30;
        double lambda = 1e-5; // Highly risk averse
        double eta = 1e-5;
        double gamma = 1e-6;
        double tau = 1.0;

        AlmgrenChrissOptimizer optimizer = new AlmgrenChrissOptimizer();

        // When
        double[] trajectory = optimizer.optimize(totalShares, numSteps, volatility, lambda, eta, gamma, tau);

        // Then: Holdings should decay faster than linear (TWAP)
        // For example, at step 1: x_1 < 80,000
        // Boundary conditions must hold: x_0 = 100,000, x_5 = 0
        assertEquals(totalShares, trajectory[0], 1e-9);
        assertTrue(trajectory[1] < 80000.0, "Risk-averse trader should liquidate faster early on");
        assertTrue(trajectory[2] < 60000.0);
        assertTrue(trajectory[3] < 40000.0);
        assertTrue(trajectory[4] < 20000.0);
        assertEquals(0.0, trajectory[numSteps], 1e-9);
    }
}
