package com.quant.pricing.agent;

import com.quant.pricing.domain.AlmgrenChrissOptimizer;
import com.quant.pricing.domain.ExecutionSimulator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionToolsTest {

    @Test
    void shouldExecuteOptimalTrajectoryTool() {
        // Arrange
        AlmgrenChrissOptimizer optimizer = new AlmgrenChrissOptimizer();
        ExecutionSimulator simulator = new ExecutionSimulator();
        ExecutionTools tools = new ExecutionTools(optimizer, simulator);

        // When
        double[] trajectory = tools.calculateOptimalTrajectory(10000.0, 5, 0.30, 0.0, 1e-5, 1e-6, 1.0);

        // Then
        assertEquals(6, trajectory.length);
        assertEquals(10000.0, trajectory[0], 1e-9);
        assertEquals(0.0, trajectory[5], 1e-9);
    }

    @Test
    void shouldExecuteSimulationTool() {
        // Arrange
        AlmgrenChrissOptimizer optimizer = new AlmgrenChrissOptimizer();
        ExecutionSimulator simulator = new ExecutionSimulator();
        ExecutionTools tools = new ExecutionTools(optimizer, simulator);
        double[] trajectory = {10000.0, 8000.0, 6000.0, 4000.0, 2000.0, 0.0};

        // When
        String json = tools.simulateExecution(100.0, trajectory, 5, 0.30, 1e-5, 1e-6, 1.0, 100);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("expectedShortfall"));
        assertTrue(json.contains("shortfallVariance"));
    }

    @Test
    void shouldHaveToolAnnotations() throws NoSuchMethodException {
        Method optimalMethod = ExecutionTools.class.getMethod("calculateOptimalTrajectory", 
                double.class, int.class, double.class, double.class, double.class, double.class, double.class);
        Method simulationMethod = ExecutionTools.class.getMethod("simulateExecution", 
                double.class, double[].class, int.class, double.class, double.class, double.class, double.class, int.class);

        assertTrue(optimalMethod.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
        assertTrue(simulationMethod.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));
    }
}
