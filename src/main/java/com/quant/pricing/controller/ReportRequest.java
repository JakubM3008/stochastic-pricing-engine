package com.quant.pricing.controller;

import com.quant.pricing.domain.ExecutionResult;

public record ReportRequest(
        SimulationRequest request,
        ExecutionResult optimalResult,
        ExecutionResult twapResult,
        ExecutionResult vwapResult
) {}
