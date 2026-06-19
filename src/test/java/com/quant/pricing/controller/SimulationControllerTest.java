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
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRunSimulationAndReturnPayload() throws Exception {
        String requestJson = """
            {
                "initialPrice": 100.0,
                "totalShares": 100000.0,
                "numSteps": 5,
                "stepVolatility": 0.02,
                "eta": 0.00001,
                "gamma": 0.000001,
                "lambda": 0.0001,
                "volumeProfile": [0.35, 0.15, 0.10, 0.15, 0.25]
            }
            """;

        mockMvc.perform(post("/api/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimalTrajectory").isArray())
                .andExpect(jsonPath("$.twapTrajectory").isArray())
                .andExpect(jsonPath("$.vwapTrajectory").isArray())
                .andExpect(jsonPath("$.optimalResult.expectedShortfall").isNumber())
                .andExpect(jsonPath("$.twapResult.expectedShortfall").isNumber())
                .andExpect(jsonPath("$.vwapResult.expectedShortfall").isNumber());
    }

    @Test
    void shouldFailSimulationOnInvalidTotalShares() throws Exception {
        String requestJson = """
            {
                "initialPrice": 100.0,
                "totalShares": -500.0,
                "numSteps": 5,
                "stepVolatility": 0.02,
                "eta": 0.00001,
                "gamma": 0.000001,
                "lambda": 0.0001,
                "volumeProfile": [0.35, 0.15, 0.10, 0.15, 0.25]
            }
            """;

        mockMvc.perform(post("/api/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldFailSimulationOnInvalidSteps() throws Exception {
        String requestJson = """
            {
                "initialPrice": 100.0,
                "totalShares": 100000.0,
                "numSteps": 0,
                "stepVolatility": 0.02,
                "eta": 0.00001,
                "gamma": 0.000001,
                "lambda": 0.0001,
                "volumeProfile": [0.35, 0.15, 0.10, 0.15, 0.25]
            }
            """;

        mockMvc.perform(post("/api/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldGenerateReportWithBypassAI() throws Exception {
        String reportRequestJson = """
            {
                "request": {
                    "initialPrice": 100.0,
                    "totalShares": 100000.0,
                    "numSteps": 5,
                    "stepVolatility": 0.02,
                    "eta": 0.00001,
                    "gamma": 0.000001,
                    "lambda": 0.0001,
                    "volumeProfile": [0.35, 0.15, 0.10, 0.15, 0.25]
                },
                "optimalResult": {
                    "expectedShortfall": 123.45,
                    "shortfallVariance": 456.78,
                    "shortfallStandardDeviation": 21.37
                },
                "twapResult": {
                    "expectedShortfall": 234.56,
                    "shortfallVariance": 567.89,
                    "shortfallStandardDeviation": 23.83
                },
                "vwapResult": {
                    "expectedShortfall": 345.67,
                    "shortfallVariance": 678.90,
                    "shortfallStandardDeviation": 26.05
                }
            }
            """;

        mockMvc.perform(post("/api/report?bypassAI=true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportRequestJson))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pre-Trade Cost & Risk Summary (Local Mode)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Optimal AC")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TWAP")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VWAP")));
    }
}
