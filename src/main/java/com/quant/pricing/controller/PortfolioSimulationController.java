package com.quant.pricing.controller;

import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionResult;
import com.quant.pricing.domain.PortfolioExecutionSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PortfolioSimulationController {

    private final AlmgrenChrissOptimizer optimizer;
    private final PortfolioExecutionSimulator portfolioSimulator;

    public PortfolioSimulationController(AlmgrenChrissOptimizer optimizer,
                                         PortfolioExecutionSimulator portfolioSimulator) {
        this.optimizer = optimizer;
        this.portfolioSimulator = portfolioSimulator;
    }

    @PostMapping("/portfolio/simulate")
    public ResponseEntity<?> runPortfolioSimulation(@RequestBody PortfolioSimulationRequest request) {
        try {
            int m = request.initialPrices().length;
            int numSteps = request.numSteps();
            double tau = 1.0;
            int numPaths = 10000;

            // 1. Calculate holding trajectories for each asset independently under Almgren-Chriss
            double[][] trajectories = new double[m][numSteps + 1];
            for (int i = 0; i < m; i++) {
                trajectories[i] = optimizer.optimize(
                        request.totalShares()[i],
                        numSteps,
                        request.stepVolatilities()[i],
                        request.lambda(),
                        request.etas()[i],
                        request.gammas()[i],
                        tau
                );
            }

            // 2. Build Covariance Matrices: Full Covariance (Correlated) vs Diagonal (Uncorrelated)
            double[][] covariance = new double[m][m];
            double[][] diagonalCovariance = new double[m][m];

            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    double covVal = request.correlationMatrix()[i][j] * request.stepVolatilities()[i] * request.stepVolatilities()[j];
                    covariance[i][j] = covVal;
                    if (i == j) {
                        diagonalCovariance[i][j] = covVal;
                    } else {
                        diagonalCovariance[i][j] = 0.0;
                    }
                }
            }

            // 3. Run Monte Carlo Simulations
            ExecutionResult correlatedRes = portfolioSimulator.simulate(
                    request.initialPrices(),
                    trajectories,
                    numSteps,
                    covariance,
                    request.etas(),
                    request.gammas(),
                    tau,
                    numPaths
            );

            ExecutionResult uncorrelatedRes = portfolioSimulator.simulate(
                    request.initialPrices(),
                    trajectories,
                    numSteps,
                    diagonalCovariance,
                    request.etas(),
                    request.gammas(),
                    tau,
                    numPaths
            );

            // 4. Calculate Risk Diversification Benefit (Volatility reduction)
            double diversificationBenefit = uncorrelatedRes.shortfallStandardDeviation() - correlatedRes.shortfallStandardDeviation();

            PortfolioSimulationResponse response = new PortfolioSimulationResponse(
                    trajectories,
                    correlatedRes,
                    uncorrelatedRes,
                    diversificationBenefit
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Handle non-positive-definite correlation matrix error from Cholesky gracefully
            return ResponseEntity.badRequest().body("EXECUTION ERROR: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("SYSTEM ERROR: " + e.getMessage());
        }
    }
}
