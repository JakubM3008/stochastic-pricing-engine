package com.quant.pricing.controller;

import com.quant.pricing.domain.ExecutionResult;

public record SimulationResponse(
        double[] optimalTrajectory,
        double[] twapTrajectory,
        double[] vwapTrajectory,
        ExecutionResult optimalResult,
        ExecutionResult twapResult,
        ExecutionResult vwapResult,
        String aiReport
) {}
