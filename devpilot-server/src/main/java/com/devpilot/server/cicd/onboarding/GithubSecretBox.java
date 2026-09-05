package com.devpilot.server.cicd.onboarding;

import com.iwebpp.crypto.TweetNaclFast;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.digests.Blake2bDigest;

/** Libsodium crypto_box_seal wire format required by GitHub Actions Secrets. */
public final class GithubSecretBox {
    private GithubSecretBox() { }

    public static String seal(String publicKey, String value) {
        byte[] receiver = Base64.getDecoder().decode(publicKey);
        if (receiver.length != 32) throw new IllegalArgumentException("Invalid GitHub encryption public key");
        var ephemeral = TweetNaclFast.Box.keyPair();
        try {
            var hash = new Blake2bDigest(192);
            hash.update(ephemeral.getPublicKey(), 0, 32);
            hash.update(receiver, 0, 32);
            byte[] nonce = new byte[24];
            hash.doFinal(nonce, 0);
            byte[] ciphertext = new TweetNaclFast.Box(receiver, ephemeral.getSecretKey())
                    .box(value.getBytes(StandardCharsets.UTF_8), nonce);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(32 + ciphertext.length)
                    .put(ephemeral.getPublicKey()).put(ciphertext).array());
        } finally {
            Arrays.fill(ephemeral.getSecretKey(), (byte) 0);
        }
    }
}
