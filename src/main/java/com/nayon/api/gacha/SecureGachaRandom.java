package com.nayon.api.gacha;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecureGachaRandom implements GachaRandom {
    private final SecureRandom random = new SecureRandom();

    @Override
    public double nextDouble() {
        return random.nextDouble();
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
