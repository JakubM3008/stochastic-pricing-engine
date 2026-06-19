package com.quant.pricing.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VwapTrajectoryGeneratorTest {

    @Test
    void shouldGenerateVwapTrajectoryProportionalToVolumeProfile() {
        // Arrange
        double totalShares = 100000.0;
        // Standard U-shaped market volume curve: 35%, 15%, 10%, 15%, 25%
        double[] volumeProfile = {0.35, 0.15, 0.10, 0.15, 0.25};

        VwapTrajectoryGenerator generator = new VwapTrajectoryGenerator();

        // When
        double[] trajectory = generator.generate(totalShares, volumeProfile);

        // Then
        assertEquals(6, trajectory.length);
        assertEquals(totalShares, trajectory[0], 1e-9);
        assertEquals(65000.0, trajectory[1], 1e-9);
        assertEquals(50000.0, trajectory[2], 1e-9);
        assertEquals(40000.0, trajectory[3], 1e-9);
        assertEquals(25000.0, trajectory[4], 1e-9);
        assertEquals(0.0, trajectory[5], 1e-9);
    }

    @Test
    void shouldThrowExceptionWhenProfileDoesNotSumToOne() {
        VwapTrajectoryGenerator generator = new VwapTrajectoryGenerator();
        double[] badProfile = {0.30, 0.10}; // sums to 0.40

        assertThrows(IllegalArgumentException.class, () -> 
            generator.generate(10000.0, badProfile)
        );
    }
}
