package com.nayon.api.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class StoreAccountHasher {

    private final byte[] key;

    public StoreAccountHasher(
            @Value("${nayon.store.account-hash-key:}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(UUID accountId) {
        if (key.length < 16) {
            throw new StoreConfigurationException(
                    "Store account hash key is not configured.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    accountId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new StoreConfigurationException(
                    "Store account hash key cannot be used.");
        }
    }
}
