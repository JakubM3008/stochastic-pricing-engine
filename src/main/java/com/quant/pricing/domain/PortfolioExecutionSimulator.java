package com.quant.pricing.domain;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class PortfolioExecutionSimulator {

    private static final Logger log = LoggerFactory.getLogger(PortfolioExecutionSimulator.class);
    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private static MethodHandle rustSimulateHandle = null;
    private static boolean useRust = false;

    static {
        try {
            // Dynamically resolve library name based on current host operating system
            String os = System.getProperty("os.name").toLowerCase();
            String libName = "librust_sim.dylib";
            if (os.contains("win")) {
                libName = "rust_sim.dll";
            } else if (os.contains("nux") || os.contains("nix")) {
                libName = "librust_sim.so";
            }

            Path libPath = Path.of("rust-sim/target/release/" + libName).toAbsolutePath();
            if (Files.exists(libPath)) {
                SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.global());
                MemorySegment symbol = lookup.find("simulate_portfolio_rust").orElse(null);
                if (symbol != null) {
                    FunctionDescriptor descriptor = FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,      // return i32 status
                            ValueLayout.JAVA_INT,      // m (size)
                            ValueLayout.JAVA_INT,      // num_steps
                            ValueLayout.ADDRESS,       // initial_prices
                            ValueLayout.ADDRESS,       // trajectories_flat
                            ValueLayout.ADDRESS,       // covariance_flat
                            ValueLayout.ADDRESS,       // etas
                            ValueLayout.ADDRESS,       // gammas
                            ValueLayout.JAVA_DOUBLE,    // tau
                            ValueLayout.JAVA_INT,      // num_paths
                            ValueLayout.ADDRESS,       // out_mean
                            ValueLayout.ADDRESS,       // out_variance
                            ValueLayout.ADDRESS        // out_std_dev
                    );
                    rustSimulateHandle = Linker.nativeLinker().downcallHandle(symbol, descriptor);
                    useRust = true;
                    log.info("NATIVE REINFORCEMENT LOADED: Java Project Panama FFM linked to Rust shared library successfully. Path: {}", libPath);
                }
            } else {
                log.warn("Rust shared library not found at {}. Falling back to Pure Java SIMD execution.", libPath);
            }
        } catch (Throwable e) {
            log.warn("Failed to load Rust native library ({}). Falling back to Pure Java SIMD execution.", e.getMessage());
        }
    }

    /**
     * Simulates liquidation of a basket of correlated assets.
     * Decouples heavy path generation to Rust via Project Panama FFM with local Java fallback.
     */
    public ExecutionResult simulate(double[] initialPrices, double[][] trajectories, int numSteps, 
                                     double[][] covariance, double[] etas, double[] gammas, double tau, int numPaths) {
        if (useRust && rustSimulateHandle != null) {
            try {
                return simulateNative(initialPrices, trajectories, numSteps, covariance, etas, gammas, tau, numPaths);
            } catch (Throwable e) {
                log.warn("Rust simulation failed, falling back to Java SIMD execution: {}", e.getMessage());
            }
        }
        return simulateJava(initialPrices, trajectories, numSteps, covariance, etas, gammas, tau, numPaths);
    }

    private ExecutionResult simulateNative(double[] initialPrices, double[][] trajectories, int numSteps, 
                                           double[][] covariance, double[] etas, double[] gammas, double tau, int numPaths) throws Throwable {
        int m = initialPrices.length;

        // Flatten 2D trajectories array (m x (numSteps+1)) to 1D for direct memory copy
        double[] trajectoriesFlat = new double[m * (numSteps + 1)];
        for (int i = 0; i < m; i++) {
            System.arraycopy(trajectories[i], 0, trajectoriesFlat, i * (numSteps + 1), numSteps + 1);
        }

        // Flatten 2D covariance array (m x m) to 1D
        double[] covarianceFlat = new double[m * m];
        for (int i = 0; i < m; i++) {
            System.arraycopy(covariance[i], 0, covarianceFlat, i * m, m);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment initialPricesSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, initialPrices);
            MemorySegment trajectoriesSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, trajectoriesFlat);
            MemorySegment covarianceSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, covarianceFlat);
            MemorySegment etasSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, etas);
            MemorySegment gammasSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, gammas);

            MemorySegment outMean = arena.allocate(ValueLayout.JAVA_DOUBLE);
            MemorySegment outVariance = arena.allocate(ValueLayout.JAVA_DOUBLE);
            MemorySegment outStdDev = arena.allocate(ValueLayout.JAVA_DOUBLE);

            int status = (int) rustSimulateHandle.invokeExact(
                    m, numSteps, initialPricesSegment, trajectoriesSegment, covarianceSegment,
                    etasSegment, gammasSegment, tau, numPaths, outMean, outVariance, outStdDev
            );

            if (status == -1) {
                throw new IllegalArgumentException("Covariance matrix is not positive-definite for Cholesky decomposition.");
            } else if (status != 0) {
                throw new RuntimeException("Rust simulation failed with status code " + status);
            }

            double mean = outMean.get(ValueLayout.JAVA_DOUBLE, 0);
            double variance = outVariance.get(ValueLayout.JAVA_DOUBLE, 0);
            double stdDev = outStdDev.get(ValueLayout.JAVA_DOUBLE, 0);

            return new ExecutionResult(mean, variance, stdDev);
        }
    }

    private ExecutionResult simulateJava(double[] initialPrices, double[][] trajectories, int numSteps, 
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
