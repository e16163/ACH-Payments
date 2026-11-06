package com.achpayments.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** PLAN A6: the Fed's signature is simulated as a detached HMAC-SHA256, in a .sig file next to each .ach file. */
@Component
public class FileSignature {

    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] key;

    public FileSignature(@Value("${app.fed.signing-key}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public static Path signatureOf(Path achFile) {
        return achFile.resolveSibling(achFile.getFileName() + ".sig");
    }

    public void sign(Path achFile) throws IOException {
        Files.writeString(signatureOf(achFile), HexFormat.of().formatHex(hmac(achFile)));
    }

    /** False if the .sig file is missing or doesn't match, so a file with no signature is treated as tampered. */
    public boolean isValid(Path achFile) throws IOException {
        Path sig = signatureOf(achFile);
        if (!Files.exists(sig)) {
            return false;
        }
        try {
            return MessageDigest.isEqual(hmac(achFile), HexFormat.of().parseHex(Files.readString(sig).trim()));
        } catch (IllegalArgumentException e) { // not hex
            return false;
        }
    }

    private byte[] hmac(Path achFile) throws IOException {
        try (InputStream in = Files.newInputStream(achFile)) {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                mac.update(chunk, 0, n);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
