package dev.oktay.kyc.extract;

import java.time.LocalDate;
import java.util.List;

/** Fields read from one document. Identifiers are already resolved from the PII vault; `problems` lists anything untrustworthy. */
public record Extracted(
        String declaredType,
        String docType,
        String fullName,
        String tckn,
        String iban,
        LocalDate birthDate,
        LocalDate expiryDate,
        LocalDate issueDate,
        boolean injection,
        List<String> problems) {}
