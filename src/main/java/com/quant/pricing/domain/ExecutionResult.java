package com.quant.pricing.domain;

public record ExecutionResult(
        double expectedShortfall,
        double shortfallVariance,
        double shortfallStandardDeviation
) {}
