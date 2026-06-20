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
        long startTime = System.nanoTime();
        try {
            int m = request.initialPrices().length;
            int numSteps = request.numSteps();
            double tau = 1.0;
            int numPaths = 10000;

            
            long optStart = System.nanoTime();
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
            long optEnd = System.nanoTime();
            long optimizationTimeNs = optEnd - optStart;

            
            double[][] originalCorr = request.correlationMatrix();
            double[][] repairedCorr = repairCorrelationMatrix(originalCorr, 1e-4);
            boolean correlationRepaired = (repairedCorr != null);
            double[][] finalCorr = correlationRepaired ? repairedCorr : originalCorr;

            
            double[][] covariance = new double[m][m];
            double[][] diagonalCovariance = new double[m][m];

            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    double covVal = finalCorr[i][j] * request.stepVolatilities()[i] * request.stepVolatilities()[j];
                    covariance[i][j] = covVal;
                    if (i == j) {
                        diagonalCovariance[i][j] = covVal;
                    } else {
                        diagonalCovariance[i][j] = 0.0;
                    }
                }
            }

            
            long simStart = System.nanoTime();
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
            long simEnd = System.nanoTime();
            long simulationTimeNs = simEnd - simStart;

            
            double diversificationBenefit = uncorrelatedRes.shortfallStandardDeviation() - correlatedRes.shortfallStandardDeviation();

            long endTime = System.nanoTime();
            long totalTimeNs = endTime - startTime;

            PortfolioSimulationResponse response = new PortfolioSimulationResponse(
                    trajectories,
                    correlatedRes,
                    uncorrelatedRes,
                    diversificationBenefit,
                    finalCorr,
                    correlationRepaired,
                    optimizationTimeNs,
                    simulationTimeNs,
                    totalTimeNs
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            
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
        
        
        Map<String, Object> methodology = new LinkedHashMap<>();
        methodology.put("optimizationFramework", "Almgren-Chriss Optimal Execution");
        methodology.put("riskModeling", "10,000-path Monte Carlo Simulation with Cholesky Decomposition");
        methodology.put("costRiskMetrics", "Expected Shortfall & Shortfall Volatility (SD)");
        jsonPayload.put("methodology", methodology);

        
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
            return agent.analyzePortfolio(prompt);
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
        double utilityDiff = Math.abs(corrUtility - uncorrUtility);
        String recommendation = corrUtility < uncorrUtility ? "Correlated Portfolio (Basket)" : "Uncorrelated Portfolio (Independent Sum)";

        String benefitText;
        if (benefit >= 0) {
            benefitText = String.format("Inter-asset correlation leads to a risk standard deviation reduction of **%.2f USD** compared to treating liquidation risks independently.", benefit);
        } else {
            benefitText = String.format("Inter-asset correlation leads to a risk standard deviation increase of **%.2f USD** (diversification penalty) compared to treating liquidation risks independently.", Math.abs(benefit));
        }

        return String.format(
            "### Portfolio Pre-Trade Risk Summary (Local Mode)\n\n" +
            "* **Correlated Portfolio (Basket)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD. Risk-Adjusted Utility: %.2f USD.\n" +
            "* **Uncorrelated Portfolio (Independent Sum)**: Expected Cost (Shortfall): %.2f USD. Risk Volatility (SD): %.2f USD. Risk-Adjusted Utility: %.2f USD.\n" +
            "* **Diversification Benefit**: %s\n" +
            "* **Execution Recommendation**: Execute the %s. We can tolerate up to **%.2f USD** of additional risk-adjusted utility trade-off in this recommended strategy before we would switch to the other portfolio.\n\n" +
            "%s",
            correlatedRes.expectedShortfall(), correlatedRes.shortfallStandardDeviation(), corrUtility,
            uncorrelatedRes.expectedShortfall(), uncorrelatedRes.shortfallStandardDeviation(), uncorrUtility,
            benefitText,
            recommendation, utilityDiff,
            note
        );
    }

    private double[][] repairCorrelationMatrix(double[][] a, double targetMinEigenvalue) {
        int n = a.length;
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            v[i][i] = 1.0;
        }

        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, d[i], 0, n);
        }

        int maxIterations = 50;
        double eps = 1e-12;

        for (int iter = 0; iter < maxIterations; iter++) {
            int p = 0;
            int q = 1;
            double maxOffDiag = Math.abs(d[0][1]);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double val = Math.abs(d[i][j]);
                    if (val > maxOffDiag) {
                        maxOffDiag = val;
                        p = i;
                        q = j;
                    }
                }
            }

            if (maxOffDiag < eps) {
                break;
            }

            double dp = d[p][p];
            double dq = d[q][q];
            double dpq = d[p][q];

            double phi = 0.5 * Math.atan2(2 * dpq, dq - dp);
            double c = Math.cos(phi);
            double s = Math.sin(phi);

            d[p][p] = c * c * dp - 2 * s * c * dpq + s * s * dq;
            d[q][q] = s * s * dp + 2 * s * c * dpq + c * c * dq;
            d[p][q] = 0.0;
            d[q][p] = 0.0;

            for (int i = 0; i < n; i++) {
                if (i != p && i != q) {
                    double dip = d[i][p];
                    double diq = d[i][q];
                    d[i][p] = c * dip - s * diq;
                    d[p][i] = d[i][p];
                    d[i][q] = s * dip + c * diq;
                    d[q][i] = d[i][q];
                }
            }

            for (int i = 0; i < n; i++) {
                double vip = v[i][p];
                double viq = v[i][q];
                v[i][p] = c * vip - s * viq;
                v[i][q] = s * vip + c * viq;
            }
        }

        boolean repaired = false;
        for (int i = 0; i < n; i++) {
            if (d[i][i] < targetMinEigenvalue) {
                d[i][i] = targetMinEigenvalue;
                repaired = true;
            }
        }

        if (!repaired) {
            return null; 
        }

        double[][] reconstructed = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double val = 0.0;
                for (int k = 0; k < n; k++) {
                    val += v[i][k] * d[k][k] * v[j][k];
                }
                reconstructed[i][j] = val;
            }
        }

        
        double[] diagSqrt = new double[n];
        for (int i = 0; i < n; i++) {
            diagSqrt[i] = Math.sqrt(reconstructed[i][i]);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                reconstructed[i][j] = reconstructed[i][j] / (diagSqrt[i] * diagSqrt[j]);
            }
        }

        return reconstructed;
    }
}
