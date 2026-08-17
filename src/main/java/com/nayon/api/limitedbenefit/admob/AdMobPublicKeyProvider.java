package com.nayon.api.limitedbenefit.admob;

import java.security.PublicKey;

@FunctionalInterface
public interface AdMobPublicKeyProvider {
    PublicKey find(long keyId);
}
