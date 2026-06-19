package com.quant.pricing.controller;

import com.quant.pricing.agent.ExecutionAgent;
import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionResult;
import com.quant.pricing.domain.ExecutionSimulator;
import com.quant.pricing.domain.VwapTrajectoryGenerator;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Enable CORS for easy local dashboard integration
public class SimulationController {

    private final ExecutionAgent agent;
    private final AlmgrenChrissOptimizer optimizer;
    private final ExecutionSimulator simulator;
    private final VwapTrajectoryGenerator vwapGenerator;

    public SimulationController(ExecutionAgent agent, AlmgrenChrissOptimizer optimizer, 
                                ExecutionSimulator simulator, VwapTrajectoryGenerator vwapGenerator) {
        this.agent = agent;
        this.optimizer = optimizer;
        this.simulator = simulator;
        this.vwapGenerator = vwapGenerator;
    }

    @PostMapping("/simulate")
    public SimulationResponse runSimulation(@RequestBody SimulationRequest request) {
        double tau = 1.0;
        int numPaths = 10000; // Standard Monte Carlo sizing

        // 1. Calculate holding trajectories
        double[] optimal = optimizer.optimize(
                request.totalShares(), request.numSteps(), request.stepVolatility(), 
                request.lambda(), request.eta(), request.gamma(), tau
        );

        double[] twap = optimizer.optimize(
                request.totalShares(), request.numSteps(), request.stepVolatility(), 
                0.0, request.eta(), request.gamma(), tau
        );

        // Standard bimodal volume curve if not provided, else use request profile
        double[] volumeProfile = request.volumeProfile();
        if (volumeProfile == null || volumeProfile.length == 0) {
            volumeProfile = new double[]{0.35, 0.15, 0.10, 0.15, 0.25};
        }
        double[] vwap = vwapGenerator.generate(request.totalShares(), volumeProfile);

        // 2. Run vectorized Monte Carlo path simulations (Loom-backed)
        ExecutionResult optimalRes = simulator.simulate(
                request.initialPrice(), optimal, request.numSteps(), 
                request.stepVolatility(), request.eta(), request.gamma(), tau, numPaths
            );

        ExecutionResult twapRes = simulator.simulate(
                request.initialPrice(), twap, request.numSteps(), 
                request.stepVolatility(), request.eta(), request.gamma(), tau, numPaths
        );

        ExecutionResult vwapRes = simulator.simulate(
                request.initialPrice(), vwap, request.numSteps(), 
                request.stepVolatility(), request.eta(), request.gamma(), tau, numPaths
        );

        // Bypass blocking LLM report compilation inside execution controller
        return new SimulationResponse(optimal, twap, vwap, optimalRes, twapRes, vwapRes, null);
    }

    @PostMapping("/report")
    public String generateReport(
            @RequestBody ReportRequest reportPayload,
            @RequestParam(name = "bypassAI", defaultValue = "false") boolean bypassAI
    ) {
        SimulationRequest request = reportPayload.request();
        ExecutionResult optimalRes = reportPayload.optimalResult();
        ExecutionResult twapRes = reportPayload.twapResult();
        ExecutionResult vwapRes = reportPayload.vwapResult();

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (bypassAI || apiKey == null || apiKey.isBlank()) {
            return formatLocalReport(optimalRes, twapRes, vwapRes, null);
        }

        try {
            String query = String.format(
                    "Order: %.0f shares @ %.2f USD. Params: lambda=%.1e, vol=%.2f, eta=%.1e, gamma=%.1e.\n" +
                    "Pre-calculated simulation results (10,000 paths):\n" +
                    "- Optimal: ES=%.2f USD, SD=%.2f USD\n" +
                    "- TWAP: ES=%.2f USD, SD=%.2f USD\n" +
                    "- VWAP: ES=%.2f USD, SD=%.2f USD",
                    request.totalShares(), request.initialPrice(),
                    request.lambda(), request.stepVolatility(), request.eta(), request.gamma(),
                    optimalRes.expectedShortfall(), optimalRes.shortfallStandardDeviation(),
                    twapRes.expectedShortfall(), twapRes.shortfallStandardDeviation(),
                    vwapRes.expectedShortfall(), vwapRes.shortfallStandardDeviation()
            );
            return agent.analyzeOrder(query);
        } catch (Exception e) {
            return formatLocalReport(optimalRes, twapRes, vwapRes, e.getMessage());
        }
    }

    private String formatLocalReport(ExecutionResult optimalRes, ExecutionResult twapRes, ExecutionResult vwapRes, String apiError) {
        String note;
        if (apiError != null) {
            note = String.format("*Note: Gemini API failed (%s). Fell back to Local Pre-Trade Report.*", apiError);
        } else {
            note = "*Note: Export your `GEMINI_API_KEY` to unlock live Gemini analysis.*";
        }

        return String.format(
            "### Pre-Trade Cost & Risk Summary (Local Mode)\n\n" +
            "* **Optimal AC**: Minimizes risk (SD: %.2f USD) by front-loading sales, but costs more (Shortfall: %.2f USD).\n" +
            "* **TWAP**: Minimizes cost (Shortfall: %.2f USD) but exposes the trade to maximum market risk (SD: %.2f USD).\n" +
            "* **VWAP**: Balanced curve matching intraday volume (Shortfall: %.2f USD, SD: %.2f USD).\n\n" +
            "%s",
            optimalRes.shortfallStandardDeviation(), optimalRes.expectedShortfall(),
            twapRes.expectedShortfall(), twapRes.shortfallStandardDeviation(),
            vwapRes.expectedShortfall(), vwapRes.shortfallStandardDeviation(),
            note
        );
    }
}
