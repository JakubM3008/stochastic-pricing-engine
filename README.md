# High-Performance Stochastic Option Pricing & Optimal Execution Engine

A high-performance quantitative finance simulator implementing the seminal **Almgren-Chriss (2000) Optimal Execution Framework**. It calculates optimal stock liquidation trajectories and simulates execution paths across Monte Carlo trials to benchmark costs (Implementation Shortfall) and volatility risk.

Built with modern **Java 23**, utilizing **Project Loom Virtual Threads** for massive Monte Carlo parallelization, and integrating a **Rust Native Simulation Engine** via **Project Panama Foreign Function & Memory (FFM) API** with a vectorized **Java Vector API (SIMD) fallback**.

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
* **Rust 1.80+** (Cargo package manager installed)
* Gradle wrapper (included)

### Running the Web Server
1. Compile the Rust native shared library:
   ```bash
   cd rust-sim
   cargo build --release
   cd ..
   ```
2. Launch the Spring Boot server to spin up the Bloomberg Terminal web frontend on port `8080`:
   ```bash
   ./gradlew bootRun
   ```
3. Navigate to:
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

## Rust Native Simulation Engine & Project Panama FFM

The simulation engine decouples heavy path generation to a native Rust shared library (`rust-sim`) to achieve high computation speed. The Java application communicates dynamically with this shared library via Project Panama's Foreign Function & Memory (FFM) API.

### Compilation
The Rust package is located in the `rust-sim/` directory. Building it in release mode compiles the codebase into a platform-specific C-compatible dynamic library:
* **macOS:** `rust-sim/target/release/librust_sim.dylib`
* **Windows:** `rust-sim/target/release/rust_sim.dll`
* **Linux:** `rust-sim/target/release/librust_sim.so`

### Dynamic Linking & FFM Integration
Dynamic linking occurs at application startup inside `PortfolioExecutionSimulator.java` via a `static` initialization block:
1. **OS Detection:** The engine dynamically resolves the library name suffix (`.dylib`, `.dll`, or `.so`) according to the host system properties.
2. **Library Lookup:** A global `Arena` loads the library off-heap:
   ```java
   SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.global());
   ```
3. **Symbol Location:** The lookup locates the `"simulate_portfolio_rust"` function pointer inside the compiled library.
4. **Downcall Handle Mapping:** A `FunctionDescriptor` is configured mapping native C parameters (lengths, scaling coefficients, and off-heap float arrays) to JVM representations using FFM layout constants (e.g., `ValueLayout.JAVA_INT`, `ValueLayout.JAVA_DOUBLE`, and `ValueLayout.ADDRESS` for pointer references). FFM constructs a type-safe `MethodHandle` to run the native function:
   ```java
   rustSimulateHandle = Linker.nativeLinker().downcallHandle(symbol, descriptor);
   ```
5. **Memory Segment Allocation:** When executing simulations, a confined arena (`Arena.ofConfined()`) allocates off-heap `MemorySegment` blocks. This maps the Java parameters directly to native pointers, avoiding heap allocation and preventing garbage collection overhead during hot execution cycles.

### Rust Multi-Threaded Implementation
In the Rust native library (`rust-sim/src/lib.rs`), the FFI entry point is exposed with C-linkage rules:
```rust
#[no_mangle]
pub extern "C" fn simulate_portfolio_rust(...) -> i32
```
* **Pointer Parsing:** The raw dynamic memory pointers passed from Java are safely converted into local Rust slices using `slice::from_raw_parts`.
* **Parallelization:** Rust splits the Monte Carlo trials across threads using `thread::spawn` according to the CPU core count queried from `num_cpus::get()`.
* **Zero-Lock Randomness:** Each worker thread runs its simulations with an isolated `StdRng` instance instantiated from entropy, which prevents global lock contention.

### Pure Java SIMD Fallback
If the dynamic library is missing or fails to load, `PortfolioExecutionSimulator` falls back to its Java-native implementation:
* **Vector API Acceleration:** Leverages CPU AVX/NEON registers using the `jdk.incubator.vector` module.
* **Apple Silicon Alignment & NEON Fix:** Hardware NEON units enforce strict vector width constraints. To prevent index out-of-bound errors, correlation and Cholesky matrices are padded to a multiple of the preferred register width (`DoubleVector.SPECIES_PREFERRED.length()`).
* **Zero GC Overhead:** Java matrices and temporary arrays are pre-allocated outside the simulation loops to avoid allocation activity on the hot paths.

---

## Containerization & Deployment

To simplify deployment and guarantee dynamic linking runs consistently in production, the application is packaged using a multi-stage Docker configuration.

### Multi-Stage Dockerfile
The `Dockerfile` is organized into three distinct stages to keep the production runtime image minimal and secure:
1. **Stage 1 (Rust Builder):** Uses a `rust:1.80-slim` base image to compile the native code. It copies the `rust-sim/` directory and executes `cargo build --release`, producing a Linux-compatible `librust_sim.so` shared library.
2. **Stage 2 (Java Builder):** Uses a `gradle:8-jdk23` base image to compile the Java code. It copies the entire project and builds the Spring Boot fat JAR by running `./gradlew bootJar --no-daemon`.
3. **Stage 3 (Production JRE):** Uses `eclipse-temurin:23-jre` as a lightweight base. It copies the Spring Boot JAR from Stage 2 and the compiled `librust_sim.so` from Stage 1 into the release folder (`rust-sim/target/release/librust_sim.so`) so the FFM linker can locate it.

### JVM Execution Flags
The final container starts the application using the following entrypoint command:
```bash
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -jar app.jar
```
* `--add-modules jdk.incubator.vector`: Instructs the JVM to load the Vector API incubator module.
* `--enable-native-access=ALL-UNNAMED`: Permits off-heap memory access and dynamic shared library linking via Project Panama FFM, bypassing default JVM security restrictions.

### Local Container Commands
You can build and run the application container locally using Docker Desktop or Colima.

1. **Start Colima** (if running on macOS with Colima):
   ```bash
   colima start
   ```
2. **Build the Container Image:**
   ```bash
   docker build -t stochastic-pricing-engine .
   ```
3. **Run the Container (Mapping Port 8080):**
   ```bash
   docker run -p 8080:8080 stochastic-pricing-engine
   ```
4. **Run the Container with Gemini AI Reporting Enabled:**
   ```bash
   docker run -p 8080:8080 -e GEMINI_API_KEY="AIzaSyYourActualKeyHere" stochastic-pricing-engine
   ```

### Serverless Deployments
* **GCP Cloud Run:** Push the container to Google Artifact Registry and deploy it serverlessly. Because Cloud Run instances operate on glibc-compatible Linux kernels, the Rust dynamic library executes natively. Allocate a minimum of 2GB of RAM and multiple vCPUs to handle concurrent Monte Carlo math workloads.
* **AWS App Runner:** Push the built image to Amazon Elastic Container Registry (ECR) and configure AWS App Runner to deploy it. App Runner scales the application instances automatically. Set up sufficient CPU resources to ensure smooth execution of the multi-threaded simulations.

---

## Efficient Frontier Chart Enhancements

The primary dashboard page (`/`) includes a visual Cost-Risk efficient frontier visualizer. Clicking `EFFICIENT FRONTIER <Go>` plots a continuous trade-off curve relating transaction costs (Expected Shortfall) to volatility risk (Standard Deviation of Shortfall).

### Noise-Free Analytical Curve Computation
Standard Monte Carlo simulation runs are subject to random statistical fluctuations (noise). To present a perfectly smooth, mathematically precise efficient frontier, coordinates are calculated *analytically*:
* **Expected Shortfall ($E[IS]$):**
  $$E[IS] = \sum_{j=1}^N t_j \left( \gamma (X_0 - x_j) + \eta \frac{t_j}{\tau} \right)$$
* **Variance ($V[IS]$):**
  $$V[IS] = \sigma^2 \tau \sum_{j=1}^N x_{j-1}^2$$
* **Standard Deviation ($SD[IS]$):**
  $$SD[IS] = \sqrt{V[IS]}$$

Where $t_j = x_{j-1} - x_j$ is the volume liquidated at step $j$, $x_j$ is the remaining stock holding, $X_0$ is the initial position size, $\gamma$ and $\eta$ are permanent and temporary impact coefficients, $\tau$ is step size, and $\sigma$ is asset volatility.

### Dynamic Sampling Range
Rather than using static parameters, the engine dynamically centers the efficient frontier around the active investor's aversion parameter. The endpoint `/api/frontier` takes the active `lambda` as a base (`baseLambda`) and samples 50 points exponentially scaled across a range spanning three orders of magnitude below and above it:
$$\lambda \in [10^{\log_{10}(\lambda) - 3}, 10^{\log_{10}(\lambda) + 3}]$$
This ensures the curve is dynamically centered and formatted for the current risk aversion level.

### Mapping Strategy Markers
The active user strategies (Optimal AC, TWAP, and VWAP) are calculated and overlaid onto the frontier curve. The UI plots these as thin minimalist `X` markers using their exact *analytical* Expected Shortfall and Standard Deviation coordinates (`optimalESAnal`, `optimalSDAnal`, etc.). This aligns the markers perfectly with the continuous curve, confirming that the Optimal AC strategy lies exactly on the Pareto frontier, while TWAP and VWAP lie above/away from it (indicating sub-optimal execution).

### Tooltip Calculations & Comparisons
Hovering over points on the chart reveals three levels of comparative metrics:
* **Frontier Line Points:** Displays the specific Cost (Expected Shortfall), Risk (Standard Deviation), and the underlying Risk Aversion ($\lambda$) value for that point on the curve.
* **Strategy Markers:** The custom Chart.js tooltip displays:
  - **Simulated Coordinates:** The actual average cost and risk standard deviation computed dynamically across the thousands of Monte Carlo trials.
  - **Analytical Coordinates:** The exact mathematical expectations of cost and risk for that strategy.
  - **Same-Risk Frontier Coordinates:** The UI interpolates the analytical frontier curve data to find the point matching the strategy's risk level (using linear interpolation between the two closest points on the frontier). It displays what the cost *should* be for an optimal strategy at that same level of risk, giving a direct measure of the strategy's efficiency gap (unnecessary cost overhead).
