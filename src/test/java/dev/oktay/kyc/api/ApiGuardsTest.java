package dev.oktay.kyc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oktay.kyc.decision.DecisionEngine;
import dev.oktay.kyc.extract.Extractor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiGuardsTest {
    private static final String ID_TEXT = "T.C. Kimlik No: 10000000146\nAdı Soyadı: AYŞE YILMAZ\nDoğum Tarihi: 12.05.1990\nGeçerlilik: 01.01.2032\n";
    private static final String GOOD = """
            {"docType":"KIMLIK","fullName":"AYŞE YILMAZ","tckn":"[TCKN_1]","iban":null,
             "birthDate":"1990-05-12","expiryDate":"2032-01-01","issueDate":null}""";

    private static KycController controller(boolean trustForwarded) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneId.of("Europe/Istanbul"));
        return new KycController(new Extractor((system, user) -> GOOD), new DecisionEngine(clock), clock, trustForwarded);
    }

    private static KycController.VerifyRequest request() {
        return new KycController.VerifyRequest(java.util.List.of(new KycController.DocumentIn("kimlik", ID_TEXT)));
    }

    private static MockHttpServletRequest from(String remote, String forwardedFor) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/kyc/verify");
        r.setRemoteAddr(remote);
        if (forwardedFor != null) r.addHeader("X-Forwarded-For", forwardedFor);
        return r;
    }

    @Test
    void spoofedForwardingHeaderDoesNotResetTheLimit() {
        KycController c = controller(false);
        int ok = 0;
        for (int i = 0; i < 25; i++) {
            ResponseEntity<?> res = c.verify(request(), from("203.0.113.9", "198.51.100." + i));
            if (res.getStatusCode().is2xxSuccessful()) ok++;
        }
        assertEquals(10, ok, "ten requests per minute per real address, whatever the header says");
    }

    @Test
    void headerIsHonouredOnlyWhenTheOperatorTrustsTheProxy() {
        KycController c = controller(true);
        int ok = 0;
        for (int i = 0; i < 25; i++) {
            if (c.verify(request(), from("10.0.0.1", "198.51.100." + i)).getStatusCode().is2xxSuccessful()) ok++;
        }
        assertEquals(25, ok);
    }

    @Test
    void oversizedAndLengthlessBodiesAreRefusedBeforeParsing() throws Exception {
        RequestSizeFilter f = new RequestSizeFilter();

        MockHttpServletRequest big = new MockHttpServletRequest("POST", "/api/kyc/verify");
        big.setContent(new byte[(int) RequestSizeFilter.MAX_BYTES + 1]);
        MockHttpServletResponse bigRes = new MockHttpServletResponse();
        MockFilterChain bigChain = new MockFilterChain();
        f.doFilter(big, bigRes, bigChain);
        assertEquals(413, bigRes.getStatus());
        assertTrue(bigChain.getRequest() == null);

        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/api/kyc/verify");
        small.setContent("{}".getBytes());
        MockHttpServletResponse smallRes = new MockHttpServletResponse();
        MockFilterChain smallChain = new MockFilterChain();
        f.doFilter(small, smallRes, smallChain);
        assertEquals(200, smallRes.getStatus());
        assertFalse(smallChain.getRequest() == null);
    }
}
