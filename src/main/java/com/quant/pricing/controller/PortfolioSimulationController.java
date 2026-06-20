package com.quant.pricing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.pricing.agent.ExecutionAgent;
import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionResult;
import com.quant.pricing.domain.PortfolioExecutionSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PortfolioSimulationController {

    private final AlmgrenChrissOptimizer optimizer;
    private final PortfolioExecutionSimulator portfolioSimulator;
    private final ExecutionAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        String template;
        try (InputStream is = getClass().getResourceAsStream("/report-summary.md")) {
            if (is == null) {
                throw new IllegalStateException("Template resource /report-summary.md not found");
            }
            template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, request.lambda(), "Failed to read template: " + e.getMessage());
        }

        Map<String, Object> jsonPayload = new LinkedHashMap<>();
        
        // 1. Methodology
        Map<String, Object> methodology = new LinkedHashMap<>();
        methodology.put("optimizationFramework", "Almgren-Chriss Optimal Execution");
        methodology.put("riskModeling", "10,000-path Monte Carlo Simulation with Cholesky Decomposition");
        methodology.put("costRiskMetrics", "Expected Shortfall & Shortfall Volatility (SD)");
        jsonPayload.put("methodology", methodology);

        // 2. Input Parameters
        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("numSteps", request.numSteps());
        inputParams.put("lambda", request.lambda());
        
        List<Map<String, Object>> assetParams = new ArrayList<>();
        for (int i = 0; i < request.initialPrices().length; i++) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("assetIndex", i + 1);
            asset.put("initialPrice", request.initialPrices()[i]);
            asset.put("shares", request.totalShares()[i]);
            asset.put("volatility", request.stepVolatilities()[i]);
            asset.put("eta", request.etas()[i]);
            asset.put("gamma", request.gammas()[i]);
            assetParams.add(asset);
        }
        inputParams.put("assets", assetParams);
        inputParams.put("correlationMatrix", request.correlationMatrix());
        jsonPayload.put("inputParameters", inputParams);

        // 3. Detailed Outputs
        Map<String, Object> detailedOutputs = new LinkedHashMap<>();
        detailedOutputs.put("trajectories", reportPayload.trajectories());
        
        Map<String, Object> correlated = new LinkedHashMap<>();
        correlated.put("expectedShortfall", correlatedResult.expectedShortfall());
        correlated.put("shortfallStandardDeviation", correlatedResult.shortfallStandardDeviation());
        correlated.put("shortfallVariance", correlatedResult.shortfallVariance());
        detailedOutputs.put("correlatedPortfolio", correlated);
        
        Map<String, Object> uncorrelated = new LinkedHashMap<>();
        uncorrelated.put("expectedShortfall", uncorrelatedResult.expectedShortfall());
        uncorrelated.put("shortfallStandardDeviation", uncorrelatedResult.shortfallStandardDeviation());
        uncorrelated.put("shortfallVariance", uncorrelatedResult.shortfallVariance());
        detailedOutputs.put("uncorrelatedPortfolio", uncorrelated);
        
        detailedOutputs.put("diversificationBenefit", benefit);
        jsonPayload.put("detailedOutputs", detailedOutputs);

        String jsonString;
        try {
            jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonPayload);
        } catch (Exception e) {
            return formatLocalPortfolioReport(correlatedResult, uncorrelatedResult, benefit, request.lambda(), "Failed to serialize JSON payload: " + e.getMessage());
        }

        String prompt = template.replace("{JSON_INPUT}", jsonString);

        try {
            return agent.analyzeOrder(prompt);
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
