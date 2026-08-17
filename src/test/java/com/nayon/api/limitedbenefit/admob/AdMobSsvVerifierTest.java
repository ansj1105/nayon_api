package com.nayon.api.limitedbenefit.admob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdMobSsvVerifierTest {
    private static final long KEY_ID = 1234567890L;
    private KeyPair keyPair;
    private AdMobSsvVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        verifier = new AdMobSsvVerifier(keyId ->
                keyId == KEY_ID ? keyPair.getPublic() : null);
    }

    @Test
    void verifiesOriginalOrderedQueryWithoutReencoding() throws Exception {
        UUID sessionId = UUID.randomUUID();
        String signed = "ad_network=5450213213286189855"
                + "&ad_unit=test-ad-unit"
                + "&custom_data=" + sessionId
                + "&reward_amount=1"
                + "&reward_item=nayon_limited_benefit"
                + "&timestamp=1786932000000"
                + "&transaction_id=abc123"
                + "&user_id=00000000-0000-0000-0000-000000000001";

        AdMobSsvCallback callback = verifier.verify(query(signed, KEY_ID));

        assertThat(callback.sessionId()).isEqualTo(sessionId);
        assertThat(callback.transactionId()).isEqualTo("abc123");
        assertThat(callback.adUnitId()).isEqualTo("test-ad-unit");
        assertThat(callback.rewardItem()).isEqualTo("nayon_limited_benefit");
        assertThat(callback.rewardAmount()).isEqualTo(1L);
        assertThat(callback.keyId()).isEqualTo(KEY_ID);
        assertThat(callback.rawQuery()).startsWith(signed + "&signature=");
    }

    @Test
    void rejectsAlteredContentUnknownKeyAndWrongParameterOrder() throws Exception {
        String signed = "ad_network=5450213213286189855"
                + "&ad_unit=test-ad-unit"
                + "&custom_data=" + UUID.randomUUID()
                + "&reward_amount=1"
                + "&reward_item=nayon_limited_benefit"
                + "&timestamp=1786932000000"
                + "&transaction_id=abc456"
                + "&user_id=00000000-0000-0000-0000-000000000001";
        String valid = query(signed, KEY_ID);

        assertThatThrownBy(() -> verifier.verify(
                valid.replace("reward_amount=1", "reward_amount=2")))
                .isInstanceOf(AdMobSsvVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(
                valid.substring(0, valid.lastIndexOf('=' ) + 1) + "999"))
                .isInstanceOf(AdMobSsvVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(
                valid.replace("&signature=", "&key_id=" + KEY_ID + "&signature=")))
                .isInstanceOf(AdMobSsvVerificationException.class);
    }

    private String query(String signed, long keyId) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signed.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
        return signed + "&signature=" + signature + "&key_id=" + keyId;
    }
}
