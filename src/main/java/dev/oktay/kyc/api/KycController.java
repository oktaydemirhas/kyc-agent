package dev.oktay.kyc.api;

import dev.oktay.kyc.decision.DecisionEngine;
import dev.oktay.kyc.decision.DecisionEngine.Decision;
import dev.oktay.kyc.extract.Extracted;
import dev.oktay.kyc.extract.Extractor;
import dev.oktay.kyc.llm.OpenAiCompatibleClient.LlmUnavailableException;
import dev.oktay.kyc.pii.Pii;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KycController {
    private static final Logger log = LoggerFactory.getLogger(KycController.class);
    private static final int PER_MINUTE = 10;
    private static final int MAX_TRACKED = 10_000;

    public record DocumentIn(
            @NotBlank @Pattern(regexp = "(?i)\\s*(kimlik|adres|banka)\\s*") String type,
            @NotBlank @Size(max = 4000) String text) {}

    public record VerifyRequest(@NotNull @Size(min = 1, max = 4) List<@NotNull @Valid DocumentIn> documents) {}

    /** What a reviewer sees: identifiers are masked, raw personal data never leaves the service. */
    public record Evidence(String docType, String fullName, String tckn, String iban, LocalDate birthDate, LocalDate expiryDate, LocalDate issueDate, boolean injectionStripped) {}

    public record VerifyResponse(Decision decision, List<Evidence> evidence) {}

    private final Extractor extractor;
    private final DecisionEngine engine;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean trustForwardedHeaders;

    public KycController(Extractor extractor, DecisionEngine engine, Clock clock,
                         @Value("${kyc.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.extractor = extractor;
        this.engine = engine;
        this.clock = clock;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @PostMapping("/api/kyc/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody VerifyRequest req, HttpServletRequest http) {
        if (!allow(clientIp(http))) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        List<Extracted> docs = req.documents().stream().map(d -> extractor.extract(d.type().strip().toUpperCase(Locale.ROOT), d.text())).toList();
        Decision decision = engine.decide(docs);
        log.info("kyc decision={} reasons={}", decision.outcome(), decision.reasons().stream().map(DecisionEngine.Reason::code).toList());
        List<Evidence> evidence = docs.stream().map(d -> new Evidence(
                d.docType(), d.fullName(), Pii.display(d.tckn()), Pii.display(d.iban()), d.birthDate(), d.expiryDate(), d.issueDate(), d.injection())).toList();
        return ResponseEntity.ok(new VerifyResponse(decision, evidence));
    }

    @ExceptionHandler(LlmUnavailableException.class)
    ResponseEntity<Map<String, String>> upstream(LlmUnavailableException e) {
        log.warn("llm unavailable: {}", e.getMessage(), e.getCause());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "upstream_error"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    /**
     * Per-IP sliding window kept in memory: a speed bump, not a quota. The table is bounded: when it is full of
     * live entries, unknown addresses are refused instead of growing the heap. Put a real limit in front of it in production.
     */
    private boolean allow(String ip) {
        long now = clock.millis();
        if (hits.size() >= MAX_TRACKED && !hits.containsKey(ip)) {
            sweep(now);
            if (hits.size() >= MAX_TRACKED) return false;
        }
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            if (q.size() >= PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
    }

    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    /**
     * Forwarding headers are client-controlled unless a proxy you run overwrites them, so they are ignored by default.
     * Set kyc.trust-forwarded-headers=true only when the service is reachable solely through such a proxy.
     */
    private String clientIp(HttpServletRequest r) {
        if (trustForwardedHeaders) {
            String cf = r.getHeader("cf-connecting-ip");
            if (cf != null && !cf.isBlank()) return truncate(cf.strip());
            String xff = r.getHeader("x-forwarded-for");
            if (xff != null && !xff.isBlank()) return truncate(xff.split(",")[0].strip());
        }
        return r.getRemoteAddr();
    }

    private static String truncate(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
