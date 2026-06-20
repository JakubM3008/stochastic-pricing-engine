# High-Performance Stochastic Option Pricing & Optimal Execution Engine

A high-performance quantitative finance simulator implementing the seminal **Almgren-Chriss (2000) Optimal Execution Framework**. It calculates optimal stock liquidation trajectories and simulates execution paths across Monte Carlo trials to benchmark costs (Implementation Shortfall) and volatility risk.

Built with modern **Java 23**, utilizing **Project Loom Virtual Threads** for massive Monte Carlo parallelization and the **Java Vector API (SIMD)** for vectorized matrix-vector multiplication (Cholesky shock generation).

---

## Key Features

* **Almgren-Chriss Optimization:** Computes the efficient frontier of optimal trading schedules under linear market impact. Decays holdings exponentially to balance transaction costs against price volatility risk.
* **Multi-Asset Portfolio & Diversification Dashboard (`/portfolio`):**
  - Interactive Bloomberg Terminal view comparing correlated basket liquidation risk against independent (uncorrelated) liquidations.
  - Allows full customization of a 3-stock basket: position sizes, initial prices, volatilities, temporary/permanent impact coefficients, and risk aversion ($\lambda$).
  - Full 3x3 Correlation Matrix input that dynamically solves for Cholesky factorizations.
  - Interactive charts plotting holding decay schedules and correlated vs. uncorrelated shortfall standard deviation.
  - Live indicators highlighting either a **Diversification Benefit** (risk reduction) or a **Diversification Penalty** (risk increase due to positive covariance).
  - **Performance Profiling Footnote:** Integrated directly into the terminal footer, detailing millisecond-level breakdowns: Java optimizer, Rust SIMD simulator (via Panama FFM), JVM marshalling overhead, DOM paint, and AI report latency.
* **Interactive Efficient Frontier Visualizer (Main Terminal `/`):**
  - Exposes a post-calculation `EFFICIENT FRONTIER <Go>` button to plot the continuous Cost-Risk efficient frontier.
  - Calculates curve coordinates analytically to eliminate Monte Carlo noise, scaling the risk-aversion ($\lambda$) range dynamically around active parameters.
  - Overlays your current Optimal AC, TWAP, and VWAP strategies directly onto the curve as thin minimalist `X` markers to illustrate Pareto efficiency.
* **SIMD Matrix Math (Java Vector API):** Vectorizes matrix-vector operations ($\mathbf{Y} = \mathbf{L}\mathbf{Z}$) inside CPU AVX/NEON registers using the new `jdk.incubator.vector` module.
  - *Apple Silicon NEON Fix:* Automatically pads correlation/Cholesky matrices to the nearest multiple of the vector width (lanes = 2 for doubles) to avoid hardware index out-of-bound errors.
  - *Zero GC Allocation:* Hot loops are 100% allocation-free by pre-allocating padded buffers outside step loops.
* **Virtual Threads (Project Loom):** Distributes independent simulation path executions across lightweight carrier threads for extreme concurrent throughput.
* **Decoupled Agentic Quant Analyst:** Integrated with **LangChain4j** and **Gemini 2.5**.
  - System instructions are externalized to [report-summary.md](file:///Users/jakubm/stochastic-pricing-engine/src/main/resources/report-summary.md).
  - Serializes all parameters and simulation outputs into a structured JSON string payload to feed to the LLM.
  - Enforces mathematically rigorous explanations, standard USD currency formatting, pre-calculated Risk-Adjusted Utilities, and trade-off tolerance switch values.

---

## Core Financial Mathematics

### 1. The Cost-Risk Utility
The engine solves the discrete optimal liquidation problem by minimizing:
$$\min \mathbb{E}[x] + \lambda \mathbb{V}[x]$$
Where:
* $\mathbb{E}[x]$ is the expected **Implementation Shortfall** (average transaction fee + market impact cost).
* $\mathbb{V}[x]$ is the variance of the shortfall (exposure to asset volatility).
* $\lambda$ is the investor's **Risk Aversion**. Higher $\lambda$ shifts the optimal path to liquidate rapidly early on, while $\lambda = 0$ converges directly to a straight linear **TWAP** (Time-Weighted Average Price) trajectory.

### 2. Multi-Asset Correlated Shocks & Diversification Benefit
To generate correlated shocks for the asset basket:
1. Decompose the covariance matrix $\mathbf{\Sigma} = \mathbf{L}\mathbf{L}^T$ using Cholesky factorization.
2. Generate independent normal shocks $\mathbf{Z} \sim N(0, \mathbf{I})$.
3. Multiply them to get correlated shocks: $\mathbf{Y} = \mathbf{L}\mathbf{Z}$. We vectorize this step by loading the lower triangular rows and shocks directly into JVM SIMD registers.
4. **Diversification Benefit** is measured by comparing the shortfall standard deviation of the uncorrelated portfolio (diagonal covariance) against the correlated portfolio:
   $$\text{Benefit} = \text{SD}_{\text{uncorrelated}} - \text{SD}_{\text{correlated}}$$
   If assets are positively correlated ($\rho > 0$), this benefit becomes negative, representing a **Diversification Penalty/Loss** due to systemic risk reinforcement.

---

## System Architecture

```
src/
├── main/
│   ├── java/com/quant/pricing/
│   │    ├── domain/                  <-- Pure Financial Mathematics & Core Simulators
│   │    │    ├── AlmgrenChrissOptimizer.java  <-- Discrete trajectory solver (Hyperbolic Sine)
│   │    │    ├── ExecutionResult.java         <-- Holds simulation mean and variance
│   │    │    ├── ExecutionSimulator.java      <-- Single-asset SIMD Monte Carlo simulator
│   │    │    └── PortfolioExecutionSimulator.java <-- Multi-asset SIMD Monte Carlo simulator (Cholesky)
│   │    │
│   │    ├── agent/                   <-- LangChain4j Agentic Interfaces & Tools
│   │    │    ├── ExecutionAgent.java          <-- Declares system messages for the quant AI
│   │    │    ├── ExecutionTools.java          <-- Tools exposed to the LLM via reflection
│   │    │    └── AgentConfiguration.java      <-- Configures AI service proxies and fallbacks
│   │    │
│   │    ├── controller/              <-- REST Endpoints
│   │    │    ├── SimulationController.java    <-- Single-asset simulation & reporting
│   │    │    ├── PortfolioSimulationController.java <-- Multi-asset basket simulation & reporting
│   │    │    └── ...
│   │    │
│   │    └── PricingEngineRunner.java <-- Bootstraps demo parameters and runs simulations
│   │
│   └── resources/
│        ├── report-summary.md        <-- Decoupled LLM system prompt / instruction set
│        └── static/                  <-- Bloomberg Terminal Frontend HTML/JS views
│             ├── index.html          <-- Standard Execution Terminal
│             ├── dynamic.html        <-- Intraday Volume Execution Terminal
│             └── portfolio.html      <-- Basket & Diversification Terminal
│
└── test/
    └── java/com/quant/pricing/
         ├── controller/
         │    ├── SimulationControllerTest.java          <-- Single-asset endpoint tests
         │    └── PortfolioSimulationControllerTest.java   <-- Multi-asset basket & Cholesky tests
         └── ...
```

---

## Build and Run Instructions

### Prerequisites
* **Java 23+** (OpenJDK 23 recommended)
* Gradle wrapper (included)

### Running the Web Server
Launch the Spring Boot server to spin up the Bloomberg Terminal web frontend on port `8080`:
```bash
./gradlew bootRun
```
Navigate to:
- Standard Execution: `http://localhost:8080/`
- Intraday Volume Profile: `http://localhost:8080/dynamic`
- Multi-Asset Portfolio: `http://localhost:8080/portfolio`

### Running the Tests
To run the full suite of unit and integration tests (including the vector padding, Cholesky, and portfolio REST endpoint suites):
```bash
./gradlew test
```

### Enabling the Gemini AI Agent
To enable live AI report generation under the terminal's `LIVE GEMINI API` toggles, export your Gemini API key:
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
