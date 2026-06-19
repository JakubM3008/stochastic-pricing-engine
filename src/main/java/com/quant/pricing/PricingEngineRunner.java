package com.quant.pricing;

import com.quant.pricing.agent.ExecutionAgent;
import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionResult;
import com.quant.pricing.domain.ExecutionSimulator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class PricingEngineRunner implements CommandLineRunner {

    private final ExecutionAgent agent;
    private final AlmgrenChrissOptimizer optimizer;
    private final ExecutionSimulator simulator;

    public PricingEngineRunner(ExecutionAgent agent, AlmgrenChrissOptimizer optimizer, ExecutionSimulator simulator) {
        this.agent = agent;
        this.optimizer = optimizer;
        this.simulator = simulator;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println("   ALMGREN-CHRISS OPTIMAL EXECUTION SIMULATOR     ");
        System.out.println("==================================================");

        // Define parameters for execution
        double initialPrice = 100.0;
        double totalShares = 1000000.0;
        int numSteps = 5;
        double stepVolatility = 0.30;
        double eta = 1e-5;
        double gamma = 1e-6;
        double tau = 1.0;
        double lambda = 1e-4; // moderate risk aversion

        System.out.println("Calculating schedules...");
        double[] optimal = optimizer.optimize(totalShares, numSteps, stepVolatility, lambda, eta, gamma, tau);
        double[] twap = optimizer.optimize(totalShares, numSteps, stepVolatility, 0.0, eta, gamma, tau);

        System.out.println("Optimal Trajectory (Holdings remaining): " + Arrays.toString(optimal));
        System.out.println("TWAP Trajectory (Holdings remaining):    " + Arrays.toString(twap));

        System.out.println("\nRunning Monte Carlo simulations (10,000 paths) on Virtual Threads...");
        ExecutionResult optimalRes = simulator.simulate(initialPrice, optimal, numSteps, stepVolatility, eta, gamma, tau, 10000);
        ExecutionResult twapRes = simulator.simulate(initialPrice, twap, numSteps, stepVolatility, eta, gamma, tau, 10000);

        System.out.printf("Optimal Execution - Expected Shortfall: %.2f USD | Std Dev: %.2f USD%n", 
                optimalRes.expectedShortfall(), optimalRes.shortfallStandardDeviation());
        System.out.printf("TWAP Execution    - Expected Shortfall: %.2f USD | Std Dev: %.2f USD%n", 
                twapRes.expectedShortfall(), twapRes.shortfallStandardDeviation());

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("\n[Note] GEMINI_API_KEY environment variable not detected.");
            System.out.println("To interact with the Agentic AI Quant Analyst, run:");
            System.out.println("  export GEMINI_API_KEY=\"your-key-here\"");
            System.out.println("  ./gradlew bootRun");
        } else {
            System.out.println("\nContacting Agentic AI Quant Analyst...");
            try {
                String query = String.format(
                        "Please analyze a liquidation order of %.0f shares at initial price %.2f. " +
                        "Compare TWAP vs Optimal Trajectory with lambda=%.1e, step volatility=%.2f, eta=%.1e, gamma=%.1e.",
                        totalShares, initialPrice, lambda, stepVolatility, eta, gamma);
                String response = agent.analyzeOrder(query);
                System.out.println("\nAI Analyst Report:");
                System.out.println(response);
            } catch (Exception e) {
                System.err.println("Agent failed: " + e.getMessage());
            }
        }
        System.out.println("==================================================");
    }
}
