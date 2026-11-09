package dev.oktay.kyc.decision;

import static dev.oktay.kyc.decision.DecisionEngine.Outcome.APPROVE;
import static dev.oktay.kyc.decision.DecisionEngine.Outcome.MANUAL_REVIEW;
import static dev.oktay.kyc.decision.DecisionEngine.Outcome.REJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oktay.kyc.extract.Extracted;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionEngineTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private final DecisionEngine engine = new DecisionEngine(Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneId.of("Europe/Istanbul")));

    private static Extracted id(String name, String tckn, LocalDate birth, LocalDate expiry) {
        return new Extracted("KIMLIK", "KIMLIK", name, tckn, null, birth, expiry, null, false, List.of());
    }

    private static Extracted address(String name, LocalDate issued) {
        return new Extracted("ADRES", "ADRES", name, null, null, null, null, issued, false, List.of());
    }

    private static Extracted bank(String name, String iban) {
        return new Extracted("BANKA", "BANKA", name, null, iban, null, null, null, false, List.of());
    }

    private final Extracted goodId = id("AYŞE YILMAZ", "10000000146", LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1));

    private static List<String> codes(DecisionEngine.Decision d) {
        return d.reasons().stream().map(DecisionEngine.Reason::code).toList();
    }

    @Test
    void cleanApplicationIsApproved() {
        var d = engine.decide(List.of(goodId, address("Ayse Yilmaz", TODAY.minusDays(30)), bank("AYŞE YILMAZ", "TR330006100519786457841326")));
        assertEquals(APPROVE, d.outcome());
        assertTrue(d.reasons().isEmpty());
    }

    @Test
    void invalidTcknIsRejected() {
        var d = engine.decide(List.of(id("AYŞE YILMAZ", "12345678901", LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1))));
        assertEquals(REJECT, d.outcome());
        assertTrue(codes(d).contains("TCKN_INVALID"));
    }

    @Test
    void expiredIdAndUnderageAreRejected() {
        assertEquals(REJECT, engine.decide(List.of(id("AYŞE YILMAZ", "10000000146", LocalDate.of(1990, 5, 12), TODAY.minusDays(1)))).outcome());
        var minor = engine.decide(List.of(id("AYŞE YILMAZ", "10000000146", TODAY.minusYears(17), LocalDate.of(2032, 1, 1))));
        assertEquals(REJECT, minor.outcome());
        assertTrue(codes(minor).contains("UNDERAGE"));
    }

    @Test
    void exactlyEighteenTodayIsAllowed() {
        var d = engine.decide(List.of(id("AYŞE YILMAZ", "10000000146", TODAY.minusYears(18), LocalDate.of(2032, 1, 1))));
        assertEquals(APPROVE, d.outcome());
    }

    @Test
    void nameMismatchNeedsAHuman() {
        var d = engine.decide(List.of(goodId, address("Fatma Demir", TODAY.minusDays(10))));
        assertEquals(MANUAL_REVIEW, d.outcome());
        assertTrue(codes(d).contains("NAME_MISMATCH"));
    }

    @Test
    void oldOrMissingAddressProofNeedsAHuman() {
        assertTrue(codes(engine.decide(List.of(goodId, address("AYŞE YILMAZ", TODAY.minusDays(91))))).contains("ADDRESS_PROOF_OLD"));
        assertEquals(APPROVE, engine.decide(List.of(goodId, address("AYŞE YILMAZ", TODAY.minusDays(90)))).outcome());
        assertTrue(codes(engine.decide(List.of(goodId, address("AYŞE YILMAZ", null)))).contains("ADDRESS_DATE_MISSING"));
        assertTrue(codes(engine.decide(List.of(goodId, address("AYŞE YILMAZ", TODAY.plusDays(3))))).contains("ADDRESS_DATE_FUTURE"));
    }

    @Test
    void badIbanNeedsAHuman() {
        var d = engine.decide(List.of(goodId, bank("AYŞE YILMAZ", "TR330006100519786457841327")));
        assertEquals(MANUAL_REVIEW, d.outcome());
        assertTrue(codes(d).contains("IBAN_INVALID"));
    }

    @Test
    void missingIdNeedsAHuman() {
        var d = engine.decide(List.of(address("AYŞE YILMAZ", TODAY.minusDays(5))));
        assertEquals(MANUAL_REVIEW, d.outcome());
        assertTrue(codes(d).contains("MISSING_ID_DOCUMENT"));
    }

    @Test
    void injectionAndUnreliableExtractionNeverApprove() {
        var injected = new Extracted("KIMLIK", "KIMLIK", "AYŞE YILMAZ", "10000000146", null, LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1), null, true, List.of());
        assertTrue(codes(engine.decide(List.of(injected))).contains("INJECTION_SUSPECTED"));
        var shaky = new Extracted("KIMLIK", "KIMLIK", "AYŞE YILMAZ", "10000000146", null, LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1), null, false, List.of("UNGROUNDED_TCKN"));
        assertEquals(MANUAL_REVIEW, engine.decide(List.of(shaky)).outcome());
    }

    @Test
    void declaredTypeMustMatchContent() {
        var wrong = new Extracted("ADRES", "KIMLIK", "AYŞE YILMAZ", "10000000146", null, LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1), null, false, List.of());
        assertTrue(codes(engine.decide(List.of(wrong))).contains("TYPE_MISMATCH"));
    }

    @Test
    void rejectBeatsReview() {
        var d = engine.decide(List.of(id("AYŞE YILMAZ", "12345678901", LocalDate.of(1990, 5, 12), LocalDate.of(2032, 1, 1)), address("Fatma Demir", TODAY.minusDays(200))));
        assertEquals(REJECT, d.outcome());
    }
}
