package com.achpayments.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.achpayments.param.ParamCache;

/**
 * Stands in for the secure channel to FedACH (PLAN A2): "sending" a file writes it to the fed/outbound folder.
 * ACH_ORIG/FED_SIM_FAIL_ATTEMPTS makes the first attempts fail, so EX-02 can be demonstrated.
 */
@Component
public class FedSimulator {

    private final ParamCache params;
    private final Path outbound;

    public FedSimulator(ParamCache params, @Value("${app.fed.outbound-dir}") String outbound) {
        this.params = params;
        this.outbound = Path.of(outbound);
    }

    /** @param attempt 1 for the first try */
    public void send(String fileName, String text, int attempt) throws IOException {
        int failures = params.get("ACH_ORIG", "FED_SIM_FAIL_ATTEMPTS").map(Integer::parseInt).orElse(0);
        if (attempt <= failures) {
            throw new IOException("Simulated FedACH connection failure on attempt " + attempt);
        }
        Files.createDirectories(outbound);
        Path temp = outbound.resolve(fileName + ".tmp");
        Files.writeString(temp, text, StandardCharsets.US_ASCII);
        Files.move(temp, outbound.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
