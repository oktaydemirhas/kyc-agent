package dev.oktay.kyc.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PiiTest {
    private static List<String> types(String s) {
        return Pii.mask(s).entities().stream().map(Pii.Entity::type).toList();
    }

    @Test
    void masksAndResolvesRoundTrip() {
        var m = Pii.mask("TC 10000000146, IBAN TR330006100519786457841326, tel 0532 123 45 67, mail a.b@x.com");
        assertEquals(List.of("IBAN", "TCKN", "TELEFON", "EPOSTA"), types("TC 10000000146, IBAN TR330006100519786457841326, tel 0532 123 45 67, mail a.b@x.com"));
        assertFalse(m.text().contains("10000000146"));
        assertFalse(m.text().contains("TR33"));
        assertEquals("10000000146", m.resolve("[TCKN_1]").orElseThrow());
        assertTrue(m.resolve("[TCKN_9]").isEmpty());
    }

    @Test
    void obfuscatedFormats() {
        assertEquals(List.of("TCKN"), types("TC10000000146"));
        assertEquals(List.of("TCKN"), types("TC: 100 000 001 46"));
        assertEquals(List.of("TCKN"), types("TC: 100-000-001-46"));
        assertEquals(List.of("IBAN"), types("TR33-0006-1005-1978-6457-8413-26"));
        assertEquals(List.of("KART"), types("kart 4111-1111-1111-1111"));
        assertEquals(List.of("KART"), types("kart 3782 822463 10005"));
        assertEquals(List.of("TELEFON"), types("+90 532 123 45 67"));
    }

    @Test
    void leavesOrdinaryNumbersAloneButMasksEveryIdCandidate() {
        // Unlike a chat assistant, KYC masks 11-digit numbers even when the checksum fails; the engine rejects them later.
        assertEquals(List.of("TCKN"), types("12345678901"));
        assertEquals(List.of(), types("Düzenleme tarihi 2026-10-01, no 450 TL, 09:45, 12.05.1990"));
    }

    @Test
    void displayKeepsOnlyTheLastTwoCharacters() {
        assertEquals("*********46", Pii.display("10000000146"));
        assertEquals(null, Pii.display(null));
    }
}
