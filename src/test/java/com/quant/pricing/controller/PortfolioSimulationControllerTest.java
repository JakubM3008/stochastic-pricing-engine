package com.quant.pricing.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
class PortfolioSimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRunPortfolioSimulationAndReturnPayload() throws Exception {
        String requestJson = """
            {
                "initialPrices": [100.0, 150.0, 80.0],
                "totalShares": [1000000.0, 800000.0, 1200000.0],
                "stepVolatilities": [0.20, 0.15, 0.25],
                "etas": [1.0e-5, 1.50e-5, 8.0e-6],
                "gammas": [1.0e-6, 8.0e-7, 1.20e-6],
                "correlationMatrix": [
                    [1.0, 0.5, 0.3],
                    [0.5, 1.0, 0.4],
                    [0.3, 0.4, 1.0]
                ],
                "lambda": 1.0e-4,
                "numSteps": 5
            }
            """;

        mockMvc.perform(post("/api/portfolio/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trajectories").isArray())
                .andExpect(jsonPath("$.correlatedResult.expectedShortfall").isNumber())
                .andExpect(jsonPath("$.uncorrelatedResult.expectedShortfall").isNumber())
                .andExpect(jsonPath("$.diversificationBenefit").isNumber())
                .andExpect(jsonPath("$.optimizationTimeNs").isNumber())
                .andExpect(jsonPath("$.simulationTimeNs").isNumber())
                .andExpect(jsonPath("$.totalTimeNs").isNumber());
    }

    @Test
    void shouldRepairPortfolioSimulationOnNonPositiveDefiniteCorrelation() throws Exception {
        
        String requestJson = """
            {
                "initialPrices": [100.0, 150.0, 80.0],
                "totalShares": [1000000.0, 800000.0, 1200000.0],
                "stepVolatilities": [0.20, 0.15, 0.25],
                "etas": [1.0e-5, 1.50e-5, 8.0e-6],
                "gammas": [1.0e-6, 8.0e-7, 1.20e-6],
                "correlationMatrix": [
                    [1.0, 0.95, 0.95],
                    [0.95, 1.0, 0.10],
                    [0.95, 0.10, 1.0]
                ],
                "lambda": 1.0e-4,
                "numSteps": 5
            }
            """;

        mockMvc.perform(post("/api/portfolio/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationRepaired").value(true))
                .andExpect(jsonPath("$.repairedCorrelationMatrix").isArray())
                .andExpect(jsonPath("$.repairedCorrelationMatrix[0][1]").isNumber())
                .andExpect(jsonPath("$.optimizationTimeNs").isNumber())
                .andExpect(jsonPath("$.simulationTimeNs").isNumber())
                .andExpect(jsonPath("$.totalTimeNs").isNumber());
    }

    @Test
    void shouldGeneratePortfolioReportWithBypassAI() throws Exception {
        String reportRequestJson = """
            {
                "request": {
                    "initialPrices": [100.0, 150.0, 80.0],
                    "totalShares": [1000000.0, 800000.0, 1200000.0],
                    "stepVolatilities": [0.20, 0.15, 0.25],
                    "etas": [1.0e-5, 1.50e-5, 8.0e-6],
                    "gammas": [1.0e-6, 8.0e-7, 1.20e-6],
                    "correlationMatrix": [
                        [1.0, 0.5, 0.3],
                        [0.5, 1.0, 0.4],
                        [0.3, 0.4, 1.0]
                    ],
                    "lambda": 1.0e-4,
                    "numSteps": 5
                },
                "correlatedResult": {
                    "expectedShortfall": 12196104.60,
                    "shortfallVariance": 3.1659e11,
                    "shortfallStandardDeviation": 562670.39
                },
                "uncorrelatedResult": {
                    "expectedShortfall": 12193511.04,
                    "shortfallVariance": 1.8766e11,
                    "shortfallStandardDeviation": 433202.29
                },
                "diversificationBenefit": -129468.10,
                "trajectories": [
                    [1000000.0, 800000.0, 600000.0, 400000.0, 200000.0, 0.0],
                    [800000.0, 640000.0, 480000.0, 320000.0, 160000.0, 0.0],
                    [1200000.0, 960000.0, 720000.0, 480000.0, 240000.0, 0.0]
                ]
            }
            """;

        mockMvc.perform(post("/api/portfolio/report?bypassAI=true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportRequestJson))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Portfolio Pre-Trade Risk Summary (Local Mode)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Correlated Portfolio (Basket)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Uncorrelated Portfolio (Independent Sum)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("diversification penalty")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Execution Recommendation")));
    }
}
