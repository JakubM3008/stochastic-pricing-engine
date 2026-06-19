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
}
