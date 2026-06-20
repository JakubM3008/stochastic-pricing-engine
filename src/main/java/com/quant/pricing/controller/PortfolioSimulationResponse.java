package com.quant.pricing.controller;

import com.quant.pricing.domain.ExecutionResult;

public record PortfolioSimulationResponse(
        double[][] trajectories,
        ExecutionResult correlatedResult,
        ExecutionResult uncorrelatedResult,
        double diversificationBenefit
) {}
