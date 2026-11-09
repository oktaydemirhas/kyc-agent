package dev.oktay.kyc.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.oktay.kyc.decision.DecisionEngine;
import dev.oktay.kyc.decision.DecisionEngine.Decision;
import dev.oktay.kyc.decision.DecisionEngine.Outcome;
import dev.oktay.kyc.extract.Extracted;
import dev.oktay.kyc.extract.Extractor;
import dev.oktay.kyc.llm.OpenAiCompatibleClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Breadth eval through the real pipeline (mask -> strip -> LLM -> checks -> rules) with a real LLM.
 * Runs only when LLM_API_KEY is set. `cases.json` is the development set; `holdout.json` was written
 * before the dev set was run and is never used to change the code.
 * Hard invariant: zero unsafe approvals, i.e. APPROVE where APPROVE was not an acceptable outcome.
 * Run one set:  mvnw test -Dtest=KycEvalTest#devSet   (or #holdoutSet)
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class KycEvalTest {
    record Doc(String type, String text) {}

    record Expect(List<Outcome> outcomes, List<String> codes) {}

    record Case(String id, String category, List<Doc> docs, Expect expect, String note) {}

    // Fixed so date rules are reproducible: all dates in the cases are relative to this day.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneId.of("Europe/Istanbul"));
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void devSet() throws Exception {
        run("cases", true);
    }

    /** Measures only: a failure here is a finding to report, not a reason to break the build or to patch the code for it. */
    @Test
    void holdoutSet() throws Exception {
        run("holdout", false);
    }

    /** Written after the structural injection signal existed, to measure it (and its false alarms) on unseen text. */
    @Test
    void holdout2Set() throws Exception {
        run("holdout2", false);
    }

    private void run(String set, boolean enforceSafety) throws Exception {
        List<Case> cases;
        try (InputStream in = getClass().getResourceAsStream("/eval/" + set + ".json")) {
            cases = json.readValue(in, new TypeReference<List<Case>>() {});
        }
        var llm = new OpenAiCompatibleClient(System.getenv("LLM_API_KEY"),
                System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com"),
                System.getenv().getOrDefault("LLM_MODEL", "deepseek-chat"));
        var extractor = new Extractor(llm);
        var engine = new DecisionEngine(CLOCK);

        int pass = 0;
        int unsafe = 0;
        Map<String, int[]> byCategory = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (Case c : cases) {
            List<Extracted> docs = c.docs().stream().map(d -> extractor.extract(d.type().toUpperCase(), d.text())).toList();
            Decision d = engine.decide(docs);
            List<String> codes = d.reasons().stream().map(DecisionEngine.Reason::code).distinct().toList();
            boolean outcomeOk = c.expect().outcomes().contains(d.outcome());
            boolean codesOk = c.expect().codes() == null || codes.containsAll(c.expect().codes());
            boolean ok = outcomeOk && codesOk;
            boolean isUnsafe = d.outcome() == Outcome.APPROVE && !c.expect().outcomes().contains(Outcome.APPROVE);
            if (ok) pass++;
            if (isUnsafe) unsafe++;
            int[] cat = byCategory.computeIfAbsent(c.category(), k -> new int[2]);
            cat[1]++;
            if (ok) cat[0]++;
            if (!ok) {
                lines.add("FAIL %s [%s]%s expected %s %s, got %s %s%n      %s".formatted(
                        c.id(), c.category(), isUnsafe ? " UNSAFE-APPROVE" : "", c.expect().outcomes(),
                        c.expect().codes() == null ? "" : c.expect().codes(), d.outcome(), codes,
                        d.reasons().stream().map(DecisionEngine.Reason::message).toList()));
            }
        }

        StringBuilder report = new StringBuilder("%n=== KYC eval: %s ===%n%d/%d passed, %d unsafe approvals%n".formatted(set, pass, cases.size(), unsafe));
        byCategory.forEach((k, v) -> report.append("  %-16s %d/%d%n".formatted(k, v[0], v[1])));
        lines.forEach(l -> report.append(l).append('\n'));
        System.out.println(report);
        Files.writeString(Path.of("target", "kyc-eval-" + set + ".txt"), report.toString(), StandardCharsets.UTF_8);

        if (enforceSafety) assertEquals(0, unsafe, "unsafe approvals");
    }
}
