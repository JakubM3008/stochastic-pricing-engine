# High-Performance Stochastic Option Pricing & Optimal Execution Engine

A high-performance quantitative finance simulator implementing the seminal **Almgren-Chriss (2000) Optimal Execution Framework**. It calculates optimal stock liquidation trajectories and simulates execution paths across Monte Carlo trials to benchmark costs (Implementation Shortfall) and volatility risk.

Built with modern **Java 23**, utilizing **Project Loom Virtual Threads** for massive Monte Carlo parallelization and the **Java Vector API (SIMD)** for vectorized matrix-vector multiplication (Cholesky shock generation).

---

## Key Features

* **Almgren-Chriss Optimization:** Computes the efficient frontier of optimal trading schedules under linear market impact. Decays holdings exponentially to balance transaction costs against price volatility risk.
* **Correlated Basket Execution:** Simulates a multi-asset basket of stocks using a covariance matrix decomposed via Cholesky factor mapping.
* **SIMD Matrix Math (Java Vector API):** Vectorizes matrix-vector operations ($\mathbf{Y} = \mathbf{L}\mathbf{Z}$) inside CPU AVX/NEON registers using the new `jdk.incubator.vector` module, executing dot products in a single CPU cycle.
* **Virtual Threads (Project Loom):** Distributes independent simulation path executions across light-weight carrier threads for extreme concurrent throughput.
* **Agentic Quant desk Analyst:** Integrated with **LangChain4j** to serve as an AI Quant Analyst that automatically runs optimizations, runs simulations, and compares execution paths.

---

## Core Financial Mathematics

### 1. The Cost-Risk Utility
The engine solves the discrete optimal liquidation problem by minimizing:
$$\min \mathbb{E}[x] + \lambda \mathbb{V}[x]$$
Where:
* $\mathbb{E}[x]$ is the expected **Implementation Shortfall** (average transaction fee + market impact cost).
* $\mathbb{V}[x]$ is the variance of the shortfall (exposure to asset volatility).
* $\lambda$ is the investor's **Risk Aversion**. Higher $\lambda$ shifts the optimal path to liquidate rapidly early on, while $\lambda = 0$ converges directly to a straight linear **TWAP** (Time-Weighted Average Price) trajectory.

### 2. Multi-Asset Correlated Shocks
To generate correlated shocks for the asset basket:
1. Decompose the covariance matrix $\mathbf{\Sigma} = \mathbf{L}\mathbf{L}^T$ using Cholesky factorization.
2. Generate independent normal shocks $\mathbf{Z} \sim N(0, \mathbf{I})$.
3. Multiply them to get correlated shocks: $\mathbf{Y} = \mathbf{L}\mathbf{Z}$.
4. We vectorize this step by loading the lower triangular rows and shocks directly into JVM SIMD registers.

---

## System Architecture

```
src/main/java/com/quant/pricing/
│
├── domain/                  <-- Pure Financial Mathematics & Core Simulators
│    ├── AlmgrenChrissOptimizer.java  <-- Discrete trajectory solver (Hyperbolic Sine)
│    ├── ExecutionResult.java         <-- Holds simulation mean and variance
│    ├── ExecutionSimulator.java      <-- Single-asset SIMD Monte Carlo simulator
│    └── PortfolioExecutionSimulator.java <-- Multi-asset SIMD Monte Carlo simulator (Cholesky)
│
├── agent/                   <-- LangChain4j Agentic Interfaces & Tools
│    ├── ExecutionAgent.java          <-- Declares system messages for the quant AI
│    ├── ExecutionTools.java          <-- Tools exposed to the LLM via reflection
│    └── AgentConfiguration.java      <-- Configures AI service proxies and fallbacks
│
└── PricingEngineRunner.java <-- Bootstraps demo parameters and runs simulations
```

---

## Build and Run Instructions

### Prerequisites
* **Java 23+** (OpenJDK 23 recommended)
* Gradle wrapper (included)

### Running the Simulator
Run the default Spring Boot command line runner. It calculates TWAP and Optimal schedules, runs 10,000 paths using SIMD and Virtual Threads, and prints the shortfall and standard deviations:

```bash
./gradlew bootRun
```

### Running the Tests
To run unit and integration tests (including the portfolio diversification benefit checks):

```bash
./gradlew test
```

### Enabling the AI Agent (Optional)
To query the LangChain4j AI Quant Analyst, get an API key from Google AI Studio, export it, and run the boot task:

```bash
export GEMINI_API_KEY="AIzaSyYourActualKeyHere"
./gradlew bootRun
```

---

## Under the Hood: Java 23 Concurrency & Hardware SIMD

### Project Loom (Virtual Threads)
Instead of allocating heavy platform threads, the Monte Carlo loops submit path execution tasks to the JVM's `newVirtualThreadPerTaskExecutor()`. When a path blocks on thread-local random generation, the JVM unmounts the virtual thread, executing other paths on the shared carrier thread. This delivers maximum CPU utilization.

### Java Vector API (SIMD)
We configure Gradle to compilation-target the vector modules:
```gradle
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += ["--add-modules", "jdk.incubator.vector"]
}
```
At runtime, Java loads arrays into registers and compiles the Java math directly into native AVX-256 or NEON instructions. This performs mathematical calculations on multiple lanes simultaneously.
