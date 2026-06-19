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

        // 3. Obtain AI Quant analyst assessment
        String aiReport;
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            aiReport = String.format(
                "### Pre-Trade Cost & Risk Analysis (Local Mode)\n\n" +
                "* **Optimal Execution** minimizes volatility exposure (Risk Std Dev: %.2f USD) by front-loading liquidation, but incurs a higher expected transaction cost (Expected Shortfall: %.2f USD).\n" +
                "* **TWAP** yields the lowest expected transaction cost (Expected Shortfall: %.2f USD) but exposes the position to high market risk (Risk Std Dev: %.2f USD).\n" +
                "* **VWAP** provides a balanced compromise matching historical volume curves, resulting in Expected Shortfall of %.2f USD and Risk Std Dev of %.2f USD.\n\n" +
                "*Note: Export your `GEMINI_API_KEY` to unlock full Gemini reasoning reports.*",
                optimalRes.shortfallStandardDeviation(), optimalRes.expectedShortfall(),
                twapRes.expectedShortfall(), twapRes.shortfallStandardDeviation(),
                vwapRes.expectedShortfall(), vwapRes.shortfallStandardDeviation()
            );
        } else {
            try {
                String query = String.format(
                        "Please analyze a liquidation order of %.0f shares at initial price %.2f. " +
                        "Compare TWAP vs Optimal Trajectory with lambda=%.1e, step volatility=%.2f, eta=%.1e, gamma=%.1e.",
                        request.totalShares(), request.initialPrice(), request.lambda(), request.stepVolatility(), request.eta(), request.gamma());
                aiReport = agent.analyzeOrder(query);
            } catch (Exception e) {
                aiReport = "AI Analyst consultation failed: " + e.getMessage();
            }
        }

        return new SimulationResponse(optimal, twap, vwap, optimalRes, twapRes, vwapRes, aiReport);
    }
}
