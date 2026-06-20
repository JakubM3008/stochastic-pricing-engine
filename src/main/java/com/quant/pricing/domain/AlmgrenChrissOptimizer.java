package com.quant.pricing.domain;

public class AlmgrenChrissOptimizer {

    public double[] optimize(double totalShares, int numSteps, double volatility, double lambda, double eta, double gamma, double tau) {
        double[] trajectory = new double[numSteps + 1];
        trajectory[0] = totalShares;
        trajectory[numSteps] = 0.0;

        if (lambda <= 0.0) {
            for (int j = 1; j < numSteps; j++) {
                trajectory[j] = totalShares * (1.0 - (double) j / numSteps);
            }
            return trajectory;
        }

        double phi = (lambda * volatility * volatility * tau) / eta;
        
        double z = 1.0 + phi / 2.0;
        double kappaTau = Math.log(z + Math.sqrt(z * z - 1.0));

        double sinhKappaN = Math.sinh(kappaTau * numSteps);

        for (int j = 1; j < numSteps; j++) {
            double sinhKappaNj = Math.sinh(kappaTau * (numSteps - j));
            trajectory[j] = totalShares * (sinhKappaNj / sinhKappaN);
        }

        return trajectory;
    }
}
