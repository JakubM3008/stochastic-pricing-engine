package com.quant.pricing.domain;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class PortfolioExecutionSimulator {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    /**
     * Simulates liquidation of a basket of correlated assets.
     * Generates correlated price shocks via Cholesky factor matrix multiplication vectorized with the Java Vector API.
     *
     * @param initialPrices S_0 for each asset
     * @param trajectories x_j for each asset (size M x N+1)
     * @param numSteps N steps
     * @param covariance step covariance matrix (size M x M)
     * @param etas temporary impact parameters for each asset
     * @param gammas permanent impact parameters for each asset
     * @param tau step duration
     * @param numPaths Monte Carlo path count
     * @return ExecutionResult of shortfall metrics
     */
    public ExecutionResult simulate(double[] initialPrices, double[][] trajectories, int numSteps, 
                                     double[][] covariance, double[] etas, double[] gammas, double tau, int numPaths) {
        
        int m = initialPrices.length;
        double initialPortfolioValueTemp = 0.0;
        for (int i = 0; i < m; i++) {
            initialPortfolioValueTemp += trajectories[i][0] * initialPrices[i];
        }
        final double initialPortfolioValue = initialPortfolioValueTemp;

        // 1. Cholesky Decomposition: Sigma = L * L^T
        double[][] L = cholesky(covariance);

        int lanes = SPECIES.length();
        int numTasks = Math.max(1, Runtime.getRuntime().availableProcessors());
        int pathsPerTask = (numPaths + numTasks - 1) / numTasks;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<List<Double>>> tasks = IntStream.range(0, numTasks)
                    .mapToObj(taskId -> (Callable<List<Double>>) () -> {
                        int startPath = taskId * pathsPerTask;
                        int endPath = Math.min(startPath + pathsPerTask, numPaths);
                        int taskPathCount = endPath - startPath;
                        List<Double> taskShortfalls = new ArrayList<>(taskPathCount);

                        int paddedSize = ((m + lanes - 1) / lanes) * lanes;
                        double[] prices = new double[m];
                        double[] shocksPadded = new double[paddedSize];
                        double[] rowPadded = new double[paddedSize];
                        double[] correlatedShocks = new double[m];

                        for (int p = 0; p < taskPathCount; p++) {
                            System.arraycopy(initialPrices, 0, prices, 0, m);
                            double cashRealized = 0.0;

                            for (int k = 1; k <= numSteps; k++) {
                                // Generate independent Gaussian shocks for all assets
                                for (int i = 0; i < m; i++) {
                                    shocksPadded[i] = ThreadLocalRandom.current().nextGaussian();
                                }

                                // Vectorized Matrix-Vector dot product (Y = L * Z)
                                for (int i = 0; i < m; i++) {
                                    System.arraycopy(L[i], 0, rowPadded, 0, m);

                                    double sum = 0.0;
                                    for (int j = 0; j < paddedSize; j += lanes) {
                                        DoubleVector vRow = DoubleVector.fromArray(SPECIES, rowPadded, j);
                                        DoubleVector vShocks = DoubleVector.fromArray(SPECIES, shocksPadded, j);
                                        sum += vRow.mul(vShocks).reduceLanes(VectorOperators.ADD);
                                    }
                                    correlatedShocks[i] = sum;
                                }

                                // Update prices and cash for each asset
                                for (int i = 0; i < m; i++) {
                                    double u_ki = trajectories[i][k - 1] - trajectories[i][k];
                                    if (u_ki <= 0.0) continue;

                                    // Evolution: asset price moves by correlated shock and permanent impact
                                    prices[i] = prices[i] + correlatedShocks[i] - gammas[i] * u_ki;
                                    
                                    // Execution price takes temporary impact hit
                                    double executionPrice = prices[i] - etas[i] * (u_ki / tau);
                                    
                                    cashRealized += u_ki * executionPrice;
                                }
                            }

                            taskShortfalls.add(initialPortfolioValue - cashRealized);
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
                            throw new RuntimeException("Portfolio task failed", e);
                        }
                    })
                    .forEach(allShortfalls::addAll);

            // Compute statistics
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
            throw new RuntimeException("Portfolio simulation was interrupted", e);
        }
    }

    private double[][] cholesky(double[][] matrix) {
        int size = matrix.length;
        double[][] l = new double[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += l[i][k] * l[j][k];
                }
                if (i == j) {
                    double val = matrix[i][i] - sum;
                    if (val <= 0.0) {
                        throw new IllegalArgumentException("Covariance matrix is not positive-definite for Cholesky decomposition.");
                    }
                    l[i][j] = Math.sqrt(val);
                } else {
                    l[i][j] = (matrix[i][j] - sum) / l[j][j];
                }
            }
        }
        return l;
    }
}
