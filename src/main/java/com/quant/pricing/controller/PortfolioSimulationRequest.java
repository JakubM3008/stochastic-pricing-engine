package com.quant.pricing.controller;

public record PortfolioSimulationRequest(
        double[] initialPrices,
        double[] totalShares,
        double[] stepVolatilities,
        double[] etas,
        double[] gammas,
        double[][] correlationMatrix,
        double lambda,
        int numSteps
) {}
