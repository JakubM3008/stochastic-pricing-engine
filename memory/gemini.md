# Antigravity Project Journal & Memory

## Communication Preferences
* **Tone/Style:** Concise, structured, concept-first.
* **Format:** Bullet points, clear diagrams, direct explanations of JVM internals and financial concepts.
* **Rules:** Avoid walls of text. Provide short checklists and code snippets with clear checkpoints.

## Learning Constraints & Goals (CRITICAL)
* **Profile:** Junior Java Developer with BSc CS + MSc Fin Math.
* **Core Concern:** Relentless delivery pressure and spamming AI leads to copying code without understanding. 
* **Core Goal:** Deeply master code patterns, JVM mechanics, and design tradeoffs to speak authoritatively in interviews.
* **Compliance Constraint:** NEVER mention specific employer names (e.g., "Goldman Sachs", "GS") in the repository code, documentation, commits, or comments. Keep all domain models and descriptions strictly generic (e.g., "Generic Corporate Treasury").
* **Agent Rule:** Proactively explain **why** decisions are made. Break down complex pieces, link to official specs/JVM behaviors, and use interactive checkpoints instead of bulk-dumping code.

## Project Profile: Almgren-Chriss Optimal Execution Simulator
* **Goal:** A high-performance quantitative simulation and optimization engine implementing the Almgren-Chriss (2000) optimal liquidation framework.
* **Tech Stack:** Java 23, Spring Boot 4.1.0, Gradle 9.5.1.
* **Key Architecture Patterns:** Ports & Adapters (Clean Architecture), Test-Driven Development (TDD).

## Core Concepts & Memory

### 1. Virtual Threads (Project Loom)
* **Carrier Threads:** OS threads that execute virtual threads. Managed by a JVM-wide `ForkJoinPool`.
* **Work Stealing:** Idle carrier threads steal virtual threads from other carrier threads' queues to maximize throughput.
* **Monte Carlo Parallelization:** In `ExecutionSimulator`, we parallelize 1,000+ stock price path simulations using virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.

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
* **Reflection-Based Tool Routing:** Mapped `ExecutionTools` methods dynamically using `@Tool` annotations to calculate trajectories and simulate shortfalls.

### Current Code Structure
* [PricingEngineApplication.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/PricingEngineApplication.java) - Application bootstrapper.
* [AlmgrenChrissOptimizer.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/domain/AlmgrenChrissOptimizer.java) - Optimizes liquidation schedules.
* [ExecutionResult.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/domain/ExecutionResult.java) - Holder for simulated shortfall mean and variance.
* [ExecutionSimulator.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/domain/ExecutionSimulator.java) - Performs virtual-threaded Monte Carlo shortfall path simulation.
* [ExecutionTools.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/agent/ExecutionTools.java) - Exposed LLM execution tools.
* [ExecutionAgent.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/agent/ExecutionAgent.java) - Defines LLM analyst system instructions.
* [AgentConfiguration.java](file:///Users/jakubm/treasury-risk-engine/src/main/java/com/quant/pricing/agent/AgentConfiguration.java) - Wire framework configurations and fallback mock models.
