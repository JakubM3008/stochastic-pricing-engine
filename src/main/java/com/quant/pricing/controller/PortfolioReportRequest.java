package com.quant.pricing.controller;

import com.quant.pricing.domain.ExecutionResult;

public record PortfolioReportRequest(
        PortfolioSimulationRequest request,
        ExecutionResult correlatedResult,
        ExecutionResult uncorrelatedResult,
        double diversificationBenefit,
        double[][] trajectories
) {}

