package com.quant.pricing.controller;

import com.quant.pricing.agent.ExecutionAgent;
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
    private final ExecutionAgent agent;

    public PortfolioSimulationController(AlmgrenChrissOptimizer optimizer,
                                         PortfolioExecutionSimulator portfolioSimulator,
                                         ExecutionAgent agent) {
        this.optimizer = optimizer;
        this.portfolioSimulator = portfolioSimulator;
        this.agent = agent;
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

    @PostMapping("/portfolio/report")
    public String generatePortfolioReport(
            @RequestBody PortfolioReportRequest reportPayload,
            @RequestParam(name = "bypassAI", defaultValue = "false") boolean bypassAI
    ) {
        PortfolioSimulationRequest request = reportPayload.request();
        ExecutionResult correlatedResult = reportPayload.correlatedResult();
        ExecutionResult uncorrelatedResult = reportPayload.uncorrelatedResult();
        double benefit = reportPayload.diversificationBenefit();

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (bypassAI || apiKey == null || apiKey.isBlank()) {
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, request.lambda(), null);
        }

        try {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("PORTFOLIO LIQUIDATION ANALYSIS & EXPLAINABILITY REPORT\n\n");
            queryBuilder.append("--- METHODOLOGY OVERVIEW ---\n");
            queryBuilder.append("- Optimization Framework: Almgren-Chriss Optimal Execution (independent holdings decay paths balancing transaction cost vs. inventory risk under risk aversion lambda).\n");
            queryBuilder.append("- Risk Modeling: 10,000-path Monte Carlo Simulation with Cholesky Decomposition factor mapping (Y = L * Z) to generate correlated asset mid-market price shocks.\n");
            queryBuilder.append("- Cost-Risk Metrics: Expected Shortfall (mean liquidation cost) and Shortfall Volatility (Standard Deviation of liquidation cost).\n\n");
            
            queryBuilder.append("--- INPUT PARAMETERS ---\n");
            queryBuilder.append(String.format("- Common Parameters: Liquidation Steps (N) = %d, Risk Aversion (lambda) = %.4e\n", request.numSteps(), request.lambda()));
            for (int i = 0; i < request.initialPrices().length; i++) {
                queryBuilder.append(String.format(
                    "- Asset S%d: Initial Price = $%.2f, Shares = %.0f, Volatility = %.4f, Temp Impact (eta) = %.4e, Perm Impact (gamma) = %.4e\n",
                    (i + 1), request.initialPrices()[i], request.totalShares()[i], request.stepVolatilities()[i], request.etas()[i], request.gammas()[i]
                ));
            }
            queryBuilder.append("- Correlation Matrix (rho):\n");
            double[][] rho = request.correlationMatrix();
            for (int i = 0; i < rho.length; i++) {
                queryBuilder.append("  ");
                for (int j = 0; j < rho[i].length; j++) {
                    queryBuilder.append(String.format("%.2f ", rho[i][j]));
                }
                queryBuilder.append("\n");
            }
            queryBuilder.append("\n");

            queryBuilder.append("--- DETAILED CALCULATION OUTPUTS ---\n");
            if (reportPayload.trajectories() != null) {
                queryBuilder.append("- Optimized Holding Decay Trajectories (per step):\n");
                for (int i = 0; i < reportPayload.trajectories().length; i++) {
                    queryBuilder.append(String.format("  * S%d Decay: ", (i + 1)));
                    for (int s = 0; s < reportPayload.trajectories()[i].length; s++) {
                        queryBuilder.append(String.format("%.1f ", reportPayload.trajectories()[i][s]));
                    }
                    queryBuilder.append("\n");
                }
            }
            queryBuilder.append(String.format(
                "- Correlated Portfolio (Basket): Expected Shortfall = $%.2f, Volatility (SD) = $%.2f, Shortfall Variance = $%.2f\n" +
                "- Uncorrelated Portfolio (Independent Sum): Expected Shortfall = $%.2f, Volatility (SD) = $%.2f, Shortfall Variance = $%.2f\n" +
                "- Calculated Diversification Benefit (Risk SD Reduction): $%.2f\n\n",
                correlatedResult.expectedShortfall(), correlatedResult.shortfallStandardDeviation(), correlatedResult.shortfallVariance(),
                uncorrelatedResult.expectedShortfall(), uncorrelatedResult.shortfallStandardDeviation(), uncorrelatedResult.shortfallVariance(),
                benefit
            ));

            queryBuilder.append("Analyze this portfolio risk profile and provide an explainability analysis. The output must have exactly 3 bullet points, under 150 words total, and include:\n");
            queryBuilder.append("1. Explanation of how the assets' correlations affect the portfolio volatility relative to the uncorrelated sum.\n");
            queryBuilder.append("2. The final calculated risk-adjusted utility (Expected Shortfall + lambda * SD^2) for both portfolios (show only the final value, do not decompose the calculation elements/steps) and explain the comparison.\n");
            queryBuilder.append("3. A clear execution suggestion choosing the portfolio with the superior (lower) risk-adjusted utility.");

            return agent.analyzeOrder(queryBuilder.toString());
        } catch (Exception e) {
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, request.lambda(), e.getMessage());
        }
    }

    private String formatLocalPortfolioReport(ExecutionResult correlatedRes, ExecutionResult uncorrelatedRes, double benefit, double lambda, String apiError) {
        String note;
        if (apiError != null) {
            note = String.format("*Note: Gemini API failed (%s). Fell back to Local Pre-Trade Report.*", apiError);
        } else {
            note = "*Note: Export your `GEMINI_API_KEY` to unlock live Gemini analysis.*";
        }

        double corrUtility = correlatedRes.expectedShortfall() + lambda * Math.pow(correlatedRes.shortfallStandardDeviation(), 2);
        double uncorrUtility = uncorrelatedRes.expectedShortfall() + lambda * Math.pow(uncorrelatedRes.shortfallStandardDeviation(), 2);

        return String.format(
            "### Portfolio Pre-Trade Risk Summary (Local Mode)\n\n" +
            "* **Correlated Portfolio (Basket)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD. Risk-Adjusted Utility: %.2f USD.\n" +
            "* **Uncorrelated Portfolio (Independent Sum)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD. Risk-Adjusted Utility: %.2f USD.\n" +
            "* **Diversification Benefit**: Inter-asset correlation leads to a risk standard deviation reduction of **%.2f USD** compared to treating liquidation risks independently.\n\n" +
            "%s",
            correlatedRes.expectedShortfall(), correlatedRes.shortfallStandardDeviation(), corrUtility,
            uncorrelatedRes.expectedShortfall(), uncorrelatedRes.shortfallStandardDeviation(), uncorrUtility,
            benefit,
            note
        );
    }
}
