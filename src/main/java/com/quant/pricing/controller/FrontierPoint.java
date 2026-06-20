package com.quant.pricing.controller;

public record FrontierPoint(
        double lambda,
        double expectedShortfall,
        double shortfallStandardDeviation
) {}
