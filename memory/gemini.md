# Project Journal & Memory

## Project Profile: Almgren-Chriss Optimal Execution Simulator
* **Goal:** A high-performance quantitative simulation and optimization engine implementing the Almgren-Chriss (2000) optimal liquidation framework.
* **Tech Stack:** Java 23, Spring Boot 4.1.0, Gradle 9.5.1.
* **Key Architecture Patterns:** Ports & Adapters (Clean Architecture), Test-Driven Development (TDD).

## Core Concepts & Memory

### 1. Virtual Threads (Project Loom)
* **Carrier Threads:** OS threads that execute virtual threads. Managed by a JVM-wide `ForkJoinPool`.
* **Work Stealing:** Idle carrier threads steal virtual threads from other carrier threads' queues to maximize throughput.
* **Monte Carlo Parallelization:** In `ExecutionSimulator` and `PortfolioExecutionSimulator`, we parallelize 1,000+ stock price path simulations using virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.

### 2. Almgren-Chriss Optimal Execution Theory
* **Objective Function:** Minimize expected implementation shortfall plus risk variance penalty:
  $$\min \mathbb{E}[x] + \lambda \mathbb{V}[x]$$
  where $\lambda$ represents the investor's risk aversion.
* **Discrete Solution:** Solves a homogeneous difference equation $x_{j+1} - (2 + \phi)x_j + x_{j-1} = 0$ using:
  $$x_j = X_0 \frac{\sinh(\kappa \tau (N - j))}{\sinh(\kappa \tau N)}$$
  where $\phi = \frac{\lambda \sigma^2 \tau}{\eta}$ and $\kappa \tau = \text{acosh}(1 + \phi / 2)$.
* **Market Impact:** Models permanent price depreciation ($\gamma$) and temporary transaction cost penalty ($\eta$).
* **TWAP Convergence:** When risk aversion $\lambda = 0$, the optimal trajectory converges cleanly to a straight linear decay line (TWAP).

### 3. Jackson 3.x in Spring Boot 4.x
* **Package Rename:** In Jackson 3.x, the package namespace has migrated from `com.fasterxml.jackson` to `tools.jackson`.
* **Immutability of ObjectMapper:** `ObjectMapper` is now fully immutable. We use `JsonMapper.builder().build()` to construct mapper instances.
* **Automatic JSR-310 (JavaTimeModule) Registration:** Time serialization works out of the box in Jackson 3.

### 4. Agentic AI & Tool Binding (LangChain4j)
* **Dynamic Proxies:** `AiServices.builder` generates runtime implementations of `ExecutionAgent` using Java dynamic proxies.
* **Reflection-Based Tool Routing:** Mapped `ExecutionTools` methods dynamically using `@Tool` annotations to calculate trajectories, generate VWAP curves, and simulate shortfalls.

### 5. Java Vector API (SIMD Mathematics)
* **Hardware registers:** Compiles to AVX-256, AVX-512, or NEON instructions.
* **Vector Loop implementation:** Uses `DoubleVector` to perform arithmetic updates on multiple price paths in a single instruction cycle, preserving individual path variances by tracking cash realizations in independent array elements.
* **Cholesky shock generation:** Performs vectorized matrix-vector dot products ($\mathbf{Y} = \mathbf{L}\mathbf{Z}$) to generate correlated price paths for multi-asset basket execution, reducing lanes via SIMD addition.

### 6. GitHub Actions CI/CD Pipeline
* **Environment Configuration:** Configured [.github/workflows/gradle.yml](file:///Users/jakubm/stochastic-pricing-engine/.github/workflows/gradle.yml) to checkout the repository, spin up **JDK 23 (Eclipse Temurin)**, cache Gradle, compile files, and execute the test suites on every push or pull request to the `main` branch.
* **Incubator support:** Passes vector module compiler and JVM runtime flags securely during compilation and test phases inside the CI environment.

### Current Code Structure
* [PricingEngineApplication.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/PricingEngineApplication.java) - Application bootstrapper.
* [AlmgrenChrissOptimizer.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/domain/AlmgrenChrissOptimizer.java) - Optimizes liquidation schedules.
* [VwapTrajectoryGenerator.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/domain/VwapTrajectoryGenerator.java) - Generates historical volume profile schedules.
* [ExecutionResult.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/domain/ExecutionResult.java) - Holder for simulated shortfall mean and variance.
* [ExecutionSimulator.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/domain/ExecutionSimulator.java) - Performs SIMD-vectorized Monte Carlo shortfall path simulation.
* [PortfolioExecutionSimulator.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/domain/PortfolioExecutionSimulator.java) - Multi-asset portfolio execution simulator with Cholesky mapping.
* [ExecutionTools.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/agent/ExecutionTools.java) - Exposed LLM execution tools.
* [ExecutionAgent.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/agent/ExecutionAgent.java) - Defines LLM analyst system instructions.
* [AgentConfiguration.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/agent/AgentConfiguration.java) - Wire framework configurations and fallback mock models.
* [PricingEngineRunner.java](file:///Users/jakubm/stochastic-pricing-engine/src/main/java/com/quant/pricing/PricingEngineRunner.java) - CommandLineRunner that prints simulation benchmarks.
* [gradle.yml](file:///Users/jakubm/stochastic-pricing-engine/.github/workflows/gradle.yml) - GitHub Actions CI compiler pipeline configuration.
