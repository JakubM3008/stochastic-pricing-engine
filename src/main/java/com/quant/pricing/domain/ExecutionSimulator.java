package com.quant.pricing.domain;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class ExecutionSimulator {

    // Preferred species detects CPU register size at runtime (AVX-256, AVX-512, or NEON)
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    /**
     * Simulates trading trajectories under the Almgren-Chriss model across multiple paths.
     * Computes the expected implementation shortfall and its variance utilizing the Java Vector API.
     */
    public ExecutionResult simulate(double initialPrice, double[] trajectory, int numSteps, 
                                     double stepVolatility, double eta, double gamma, double tau, int numPaths) {
        
        double totalShares = trajectory[0];
        int lanes = SPECIES.length();

        // Calculate parallel simulation tasks for batches of paths
        // We divide the numPaths into vectorized chunks run concurrently via Virtual Threads
        int numTasks = Math.max(1, Runtime.getRuntime().availableProcessors());
        int pathsPerTask = (numPaths + numTasks - 1) / numTasks;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<List<Double>>> tasks = IntStream.range(0, numTasks)
                    .mapToObj(taskId -> (Callable<List<Double>>) () -> {
                        int startPath = taskId * pathsPerTask;
                        int endPath = Math.min(startPath + pathsPerTask, numPaths);
                        int taskPathCount = endPath - startPath;
                        List<Double> taskShortfalls = new ArrayList<>(taskPathCount);

                        // Process paths in vectorized batches of size 'lanes'
                        int vectorizedBound = (taskPathCount / lanes) * lanes;

                        double[] pricesArray = new double[lanes];
                        double[] shocksArray = new double[lanes];
                        double[] executionPricesArray = new double[lanes];
                        double[] cashRealizedArray = new double[lanes]; // Track cash for each path independently

                        // Volatility scalar broadcasted into CPU register
                        DoubleVector vVolatility = DoubleVector.broadcast(SPECIES, stepVolatility * Math.sqrt(tau));

                        for (int p = 0; p < vectorizedBound; p += lanes) {
                            // Initialize pricing registers and cash accumulators for 'lanes' paths
                            for (int i = 0; i < lanes; i++) {
                                pricesArray[i] = initialPrice;
                                cashRealizedArray[i] = 0.0;
                            }

                            for (int k = 1; k <= numSteps; k++) {
                                double u_k = trajectory[k - 1] - trajectory[k];
                                if (u_k <= 0.0) continue;

                                // Generate Gaussian shocks for all lanes
                                for (int i = 0; i < lanes; i++) {
                                    shocksArray[i] = ThreadLocalRandom.current().nextGaussian();
                                }

                                // Load arrays into vector registers
                                DoubleVector vPrices = DoubleVector.fromArray(SPECIES, pricesArray, 0);
                                DoubleVector vShocks = DoubleVector.fromArray(SPECIES, shocksArray, 0);

                                DoubleVector vPermanent = DoubleVector.broadcast(SPECIES, gamma * u_k);
                                DoubleVector vTemporary = DoubleVector.broadcast(SPECIES, eta * (u_k / tau));

                                // SIMD Math: price = price + vol * shock - permanent_impact
                                DoubleVector vNextPrices = vPrices.add(vVolatility.mul(vShocks)).sub(vPermanent);
                                
                                // SIMD Math: execution_price = price - temporary_impact
                                DoubleVector vExecutionPrices = vNextPrices.sub(vTemporary);

                                // Write back vectors to arrays
                                vNextPrices.intoArray(pricesArray, 0);
                                vExecutionPrices.intoArray(executionPricesArray, 0);

                                // Accumulate cash for each path separately to preserve math correctness (variance)
                                for (int i = 0; i < lanes; i++) {
                                    cashRealizedArray[i] += u_k * executionPricesArray[i];
                                }
                            }
                            
                            // Add shortfall result for each independent path
                            for (int i = 0; i < lanes; i++) {
                                taskShortfalls.add((totalShares * initialPrice) - cashRealizedArray[i]);
                            }
                        }

                        // Scalar fallback for remaining paths that don't fit vector width
                        for (int p = vectorizedBound; p < taskPathCount; p++) {
                            double stockPrice = initialPrice;
                            double cashFromLiquidation = 0.0;

                            for (int k = 1; k <= numSteps; k++) {
                                double u_k = trajectory[k - 1] - trajectory[k];
                                if (u_k <= 0.0) continue;

                                double z_k = ThreadLocalRandom.current().nextGaussian();
                                stockPrice = stockPrice + stepVolatility * Math.sqrt(tau) * z_k - gamma * u_k;
                                double executionPrice = stockPrice - eta * (u_k / tau);
                                cashFromLiquidation += u_k * executionPrice;
                            }
                            taskShortfalls.add((totalShares * initialPrice) - cashFromLiquidation);
                        }

                        return taskShortfalls;
                    })
                    .toList();

            List<Double> allShortfalls = new ArrayList<>(numPaths);
            executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.resultNow();
                        } catch (Exception e) {
                            throw new RuntimeException("Execution task failed", e);
                        }
                    })
                    .forEach(allShortfalls::addAll);

            // Compute Statistics
            double sum = 0.0;
            for (double shortfall : allShortfalls) {
                sum += shortfall;
            }
            double mean = sum / numPaths;

            double sumOfSquares = 0.0;
            for (double shortfall : allShortfalls) {
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
