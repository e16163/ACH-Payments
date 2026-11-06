package com.achpayments.inbound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSignatureTest {

    private final FileSignature signature = new FileSignature("test-key");

    @Test
    void acceptsTheSignedFileAndRefusesAnythingElse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f.ach");
        Files.writeString(file, "contents");
        assertFalse(signature.isValid(file), "no signature yet");
        signature.sign(file);
        assertTrue(signature.isValid(file));
        Files.writeString(file, "Contents");
        assertFalse(signature.isValid(file), "changed after signing");
        Files.writeString(FileSignature.signatureOf(file), "not hex");
        assertFalse(signature.isValid(file), "a signature that isn't hex");
        signature.sign(file);
        assertFalse(new FileSignature("another-key").isValid(file), "signed with another key");
    }
}
