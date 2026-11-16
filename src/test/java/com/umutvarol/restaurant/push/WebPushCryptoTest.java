package com.umutvarol.restaurant.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The worked example of RFC 8291, section 5: the same keys and salt must give the same bytes, to the last one. */
class WebPushCryptoTest {

    private static byte[] b64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    @Test
    void encryptsTheRfc8291ExampleToTheByte() throws Exception {
        KeyPair sender = WebPushCrypto.keyPair(
                b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
                b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                b64("BTBZMqHH6r4Tts7J_aSIgg"), sender, b64("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_"
                        + "yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void keysGoToRawFormAndBack() throws Exception {
        KeyPair pair = WebPushCrypto.newKeyPair();
        byte[] pub = WebPushCrypto.raw((java.security.interfaces.ECPublicKey) pair.getPublic());
        assertThat(pub).hasSize(65);
        assertThat(WebPushCrypto.raw(WebPushCrypto.publicKey(pub))).isEqualTo(pub);
    }
}
