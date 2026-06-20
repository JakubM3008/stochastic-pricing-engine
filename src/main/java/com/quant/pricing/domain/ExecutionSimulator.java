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

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    public ExecutionResult simulate(double initialPrice, double[] trajectory, int numSteps, 
                                     double stepVolatility, double eta, double gamma, double tau, int numPaths) {
        
        double totalShares = trajectory[0];
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

                        int vectorizedBound = (taskPathCount / lanes) * lanes;

                        double[] pricesArray = new double[lanes];
                        double[] shocksArray = new double[lanes];
                        double[] executionPricesArray = new double[lanes];
                        double[] cashRealizedArray = new double[lanes];

                        DoubleVector vVolatility = DoubleVector.broadcast(SPECIES, stepVolatility * Math.sqrt(tau));

                        for (int p = 0; p < vectorizedBound; p += lanes) {
                            for (int i = 0; i < lanes; i++) {
                                pricesArray[i] = initialPrice;
                                cashRealizedArray[i] = 0.0;
                            }

                            for (int k = 1; k <= numSteps; k++) {
                                double u_k = trajectory[k - 1] - trajectory[k];
                                if (u_k <= 0.0) continue;

                                for (int i = 0; i < lanes; i++) {
                                    shocksArray[i] = ThreadLocalRandom.current().nextGaussian();
                                }

                                DoubleVector vPrices = DoubleVector.fromArray(SPECIES, pricesArray, 0);
                                DoubleVector vShocks = DoubleVector.fromArray(SPECIES, shocksArray, 0);

                                DoubleVector vPermanent = DoubleVector.broadcast(SPECIES, gamma * u_k);
                                DoubleVector vTemporary = DoubleVector.broadcast(SPECIES, eta * (u_k / tau));

                                DoubleVector vNextPrices = vPrices.add(vVolatility.mul(vShocks)).sub(vPermanent);
                                DoubleVector vExecutionPrices = vNextPrices.sub(vTemporary);

                                vNextPrices.intoArray(pricesArray, 0);
                                vExecutionPrices.intoArray(executionPricesArray, 0);

                                for (int i = 0; i < lanes; i++) {
                                    cashRealizedArray[i] += u_k * executionPricesArray[i];
                                }
                            }
                            
                            for (int i = 0; i < lanes; i++) {
                                taskShortfalls.add((totalShares * initialPrice) - cashRealizedArray[i]);
                            }
                        }

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
