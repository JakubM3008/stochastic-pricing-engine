package com.quant.pricing.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionSimulatorTest {

    @Test
    void shouldSimulateExecutionAndComputeShortfallMetrics() {
        // Arrange
        double initialPrice = 100.0;
        double totalShares = 100000.0;
        int numSteps = 5;
        double volatility = 0.30;
        double eta = 1e-4;    // Higher temporary impact to make shortfall visible
        double gamma = 1e-5;  // Permanent impact
        double tau = 1.0;
        int numPaths = 1000;

        AlmgrenChrissOptimizer optimizer = new AlmgrenChrissOptimizer();
        double[] optimalTrajectory = optimizer.optimize(totalShares, numSteps, volatility, 1e-2, eta, gamma, tau);
        double[] twapTrajectory = optimizer.optimize(totalShares, numSteps, volatility, 0.0, eta, gamma, tau);

        ExecutionSimulator simulator = new ExecutionSimulator();

        // When
        ExecutionResult optimalResult = simulator.simulate(initialPrice, optimalTrajectory, numSteps, volatility, eta, gamma, tau, numPaths);
        ExecutionResult twapResult = simulator.simulate(initialPrice, twapTrajectory, numSteps, volatility, eta, gamma, tau, numPaths);

        // Then
        assertNotNull(optimalResult);
        assertNotNull(twapResult);
        
        // Expected shortfall should be positive (impact costs money)
        assertTrue(optimalResult.expectedShortfall() > 0.0);
        assertTrue(twapResult.expectedShortfall() > 0.0);

        // Volatility risk: Optimal trajectory is designed to trade faster, reducing exposure to price risk.
        // Therefore, the variance/std-dev of shortfall for the optimal trajectory should be LOWER than TWAP.
        assertTrue(optimalResult.shortfallVariance() < twapResult.shortfallVariance(), 
                "Optimal trajectory with lambda > 0 must reduce shortfall variance compared to TWAP");
    }
}
