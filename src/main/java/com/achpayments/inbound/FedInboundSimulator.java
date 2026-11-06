package com.achpayments.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Stands in for the FedACH side (PLAN A2): "delivering" a sample writes it and its signature to the fed/inbound folder. */
@Component
public class FedInboundSimulator {

    /** The sample files, rebuilt as valid files (docs/DB-MODEL.md), and the two that show the exceptions. */
    public enum Sample {
        PAYROLL("sample1-payroll.ach"),
        RETAIL("sample2-retail.ach"),
        OUT_OF_BALANCE("sample3-out-of-balance.ach"),
        TAMPERED("sample1-payroll.ach");

        private final String resource;

        Sample(String resource) {
            this.resource = resource;
        }
    }

    private final FileSignature signature;
    private final Path inbound;

    public FedInboundSimulator(FileSignature signature, @Value("${app.fed.inbound-dir}") String inbound) {
        this.signature = signature;
        this.inbound = Path.of(inbound);
    }

    public void deliver(Sample sample) throws IOException {
        Files.createDirectories(inbound);
        Path file = inbound.resolve(sample.name() + ".ach");
        try (InputStream in = new ClassPathResource("fed-samples/" + sample.resource).getInputStream()) {
            Files.writeString(file, new String(in.readAllBytes(), StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
        }
        signature.sign(file);
        if (sample == Sample.TAMPERED) { // changed in transit, after the Fed signed it
            String text = Files.readString(file);
            Files.writeString(file, text.replace("0000150000EMP000000001", "0000950000EMP000000001"));
        }
    }
}
