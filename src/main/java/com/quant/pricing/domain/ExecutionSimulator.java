package com.quant.pricing.domain;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class ExecutionSimulator {

    /**
     * Simulates trading trajectories under the Almgren-Chriss model across multiple paths.
     * Computes the expected implementation shortfall and its variance.
     *
     * @param initialPrice S_0, initial asset price
     * @param trajectory x_j, remaining shares at each step
     * @param numSteps N, number of trading intervals
     * @param stepVolatility volatility per step (e.g., daily volatility)
     * @param eta temporary market impact parameter
     * @param gamma permanent market impact parameter
     * @param tau step size (typically 1.0)
     * @param numPaths number of Monte Carlo paths to run
     * @return ExecutionResult containing mean, variance, and standard deviation of shortfall
     */
    public ExecutionResult simulate(double initialPrice, double[] trajectory, int numSteps, 
                                     double stepVolatility, double eta, double gamma, double tau, int numPaths) {
        
        double totalShares = trajectory[0];

        // Parallelize Monte Carlo path simulation using Virtual Threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Double>> tasks = IntStream.range(0, numPaths)
                    .mapToObj(pathId -> (Callable<Double>) () -> {
                        double stockPrice = initialPrice;
                        double cashFromLiquidation = 0.0;

                        for (int k = 1; k <= numSteps; k++) {
                            double u_k = trajectory[k - 1] - trajectory[k]; // shares sold at this step
                            if (u_k <= 0.0) continue;

                            double z_k = ThreadLocalRandom.current().nextGaussian();
                            
                            // Stock price evolution (incorporating permanent impact)
                            stockPrice = stockPrice + stepVolatility * Math.sqrt(tau) * z_k - gamma * u_k;

                            // Actual execution price obtained (incorporating temporary impact)
                            double executionPrice = stockPrice - eta * (u_k / tau);

                            cashFromLiquidation += u_k * executionPrice;
                        }

                        // Implementation Shortfall = Paper Value at S_0 - Realized Cash
                        return (totalShares * initialPrice) - cashFromLiquidation;
                    })
                    .toList();

            double[] shortfalls = executor.invokeAll(tasks).stream()
                    .mapToDouble(future -> {
                        try {
                            return future.resultNow();
                        } catch (Exception e) {
                            throw new RuntimeException("Execution path simulation failed", e);
                        }
                    })
                    .toArray();

            // Calculate statistics
            double sum = 0.0;
            for (double shortfall : shortfalls) {
                sum += shortfall;
            }
            double mean = sum / numPaths;

            double sumOfSquares = 0.0;
            for (double shortfall : shortfalls) {
                double diff = shortfall - mean;
                sumOfSquares += diff * diff;
            }
            double variance = sumOfSquares / (numPaths - 1);
            double stdDev = Math.sqrt(variance);

            return new ExecutionResult(mean, variance, stdDev);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Simulation execution was interrupted", e);
        }
    }
}
