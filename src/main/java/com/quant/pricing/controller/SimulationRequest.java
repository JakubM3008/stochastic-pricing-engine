package com.quant.pricing.controller;

public record SimulationRequest(
        double initialPrice,
        double totalShares,
        int numSteps,
        double stepVolatility,
        double eta,
        double gamma,
        double lambda,
        double[] volumeProfile
) {
    public SimulationRequest {
        if (numSteps <= 0) throw new IllegalArgumentException("numSteps must be greater than zero");
        if (totalShares <= 0.0) throw new IllegalArgumentException("totalShares must be greater than zero");
        if (initialPrice <= 0.0) throw new IllegalArgumentException("initialPrice must be greater than zero");
    }
}
