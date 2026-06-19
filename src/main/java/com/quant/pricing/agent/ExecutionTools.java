package com.quant.pricing.agent;

import com.quant.pricing.domain.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import tools.jackson.databind.json.JsonMapper;

public class ExecutionTools {
    private final AlmgrenChrissOptimizer optimizer;
    private final ExecutionSimulator simulator;
    private final VwapTrajectoryGenerator vwapGenerator;
    private final JsonMapper jsonMapper;

    public ExecutionTools(AlmgrenChrissOptimizer optimizer, ExecutionSimulator simulator, VwapTrajectoryGenerator vwapGenerator) {
        this.optimizer = optimizer;
        this.simulator = simulator;
        this.vwapGenerator = vwapGenerator;
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Tool("Calculate the Almgren-Chriss optimal trading trajectory (shares remaining at each step). Returns an array of target holdings.")
    public double[] calculateOptimalTrajectory(
            @P("Total shares to liquidate") double totalShares,
            @P("Number of steps in the simulation") int numSteps,
            @P("Volatility per step") double volatility,
            @P("Risk aversion coefficient lambda") double lambda,
            @P("Temporary market impact eta") double eta,
            @P("Permanent market impact gamma") double gamma,
            @P("Duration of each step tau") double tau
    ) {
        return optimizer.optimize(totalShares, numSteps, volatility, lambda, eta, gamma, tau);
    }

    @Tool("Generate a VWAP target holding trajectory (shares remaining at each step) based on a historical volume profile curve (which must sum to 1.0).")
    public double[] generateVwapTrajectory(
            @P("Total shares to liquidate") double totalShares,
            @P("Array of historical volume fractions for each step") double[] volumeProfile
    ) {
        return vwapGenerator.generate(totalShares, volumeProfile);
    }

    @Tool("Simulate the execution of a trading trajectory across Monte Carlo paths under Almgren-Chriss impact conditions. Returns expected shortfall, variance, and standard deviation.")
    public String simulateExecution(
            @P("Initial asset price") double initialPrice,
            @P("Trajectory of remaining holdings array") double[] trajectory,
            @P("Number of steps in the simulation") int numSteps,
            @P("Volatility per step") double stepVolatility,
            @P("Temporary market impact eta") double eta,
            @P("Permanent market impact gamma") double gamma,
            @P("Duration of each step tau") double tau,
            @P("Number of simulation paths") int numPaths
    ) {
        try {
            ExecutionResult result = simulator.simulate(initialPrice, trajectory, numSteps, stepVolatility, eta, gamma, tau, numPaths);
            return jsonMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Simulation failed: " + e.getMessage();
        }
    }
}
