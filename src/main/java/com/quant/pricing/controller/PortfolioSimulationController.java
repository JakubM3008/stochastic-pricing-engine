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
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, null);
        }

        try {
            String query = String.format(
                    "Portfolio liquidation of 3 assets:\n" +
                    "- S1: %.0f shares, S2: %.0f shares, S3: %.0f shares.\n" +
                    "- Volatilities: S1=%.2f, S2=%.2f, S3=%.2f.\n" +
                    "- Correlations: S1-S2=%.2f, S1-S3=%.2f, S2-S3=%.2f.\n" +
                    "Pre-calculated basket simulation results (10,000 paths):\n" +
                    "- Correlated Portfolio: ES=%.2f USD, SD=%.2f USD\n" +
                    "- Uncorrelated Portfolio: ES=%.2f USD, SD=%.2f USD\n" +
                    "- Diversification Benefit (Risk SD Reduction): %.2f USD.\n" +
                    "Analyze the portfolio risk profile. Provide:\n" +
                    "1. Bullet 1: Explain why the correlated risk differs from the uncorrelated sum based on correlations.\n" +
                    "2. Bullet 2: Explain why expected shortfall is identical but risk is reduced due to diversification.\n" +
                    "3. Bullet 3: Provide a clear execution suggestion under these correlation conditions.",
                    request.totalShares()[0], request.totalShares()[1], request.totalShares()[2],
                    request.stepVolatilities()[0], request.stepVolatilities()[1], request.stepVolatilities()[2],
                    request.correlationMatrix()[0][1], request.correlationMatrix()[0][2], request.correlationMatrix()[1][2],
                    correlatedResult.expectedShortfall(), correlatedResult.shortfallStandardDeviation(),
                    uncorrelatedResult.expectedShortfall(), uncorrelatedResult.shortfallStandardDeviation(),
                    benefit
            );
            return agent.analyzeOrder(query);
        } catch (Exception e) {
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, e.getMessage());
        }
    }

    private String formatLocalPortfolioReport(ExecutionResult correlatedRes, ExecutionResult uncorrelatedRes, double benefit, String apiError) {
        String note;
        if (apiError != null) {
            note = String.format("*Note: Gemini API failed (%s). Fell back to Local Pre-Trade Report.*", apiError);
        } else {
            note = "*Note: Export your `GEMINI_API_KEY` to unlock live Gemini analysis.*";
        }

        return String.format(
            "### Portfolio Pre-Trade Risk Summary (Local Mode)\n\n" +
            "* **Correlated Portfolio (Basket)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD.\n" +
            "* **Uncorrelated Portfolio (Independent Sum)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD.\n" +
            "* **Diversification Benefit**: Inter-asset correlation leads to a risk standard deviation reduction of **%.2f USD** compared to treating liquidation risks independently.\n\n" +
            "%s",
            correlatedRes.expectedShortfall(), correlatedRes.shortfallStandardDeviation(),
            uncorrelatedRes.expectedShortfall(), uncorrelatedRes.shortfallStandardDeviation(),
            benefit,
            note
        );
    }
}
