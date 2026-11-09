package dev.oktay.kyc.pii;

import dev.oktay.kyc.check.Checks;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces personal data with placeholders before anything is sent to an LLM, and keeps the originals
 * in a per-request vault. The model can only hand a placeholder back; the server resolves it.
 */
public final class Pii {
    private Pii() {}

    public record Entity(String type, String placeholder, String value) {}

    public record Masked(String text, List<Entity> entities) {
        public Optional<String> resolve(String placeholder) {
            return entities.stream().filter(e -> e.placeholder().equals(placeholder)).map(Entity::value).findFirst();
        }

        public boolean contains(String value) {
            return entities.stream().anyMatch(e -> e.value().equals(value));
        }
    }

    private record Rule(String type, Pattern re, Predicate<String> ok) {}

    // Digit runs use lookarounds instead of \b so values glued to letters are still found.
    private static final List<Rule> RULES = List.of(
        new Rule("IBAN", Pattern.compile("(?<![A-Za-z0-9])TR[\\s.-]?\\d{2}(?:[\\s.-]?\\d{4}){5}[\\s.-]?\\d{2}(?!\\d)", Pattern.CASE_INSENSITIVE), s -> true),
        // No Luhn check on purpose: over-masking an invoice number is harmless, leaking a mistyped card is not.
        new Rule("KART", Pattern.compile("(?<!\\d)(?:(?:\\d{4}[\\s-]?){3}\\d{4}|\\d{4}[\\s-]?\\d{6}[\\s-]?\\d{5})(?!\\d)"), s -> true),
        // Masked even when the checksum fails: the vault must hold every candidate so the decision engine can
        // report TCKN_INVALID. Leaving a bad number in the text made the model echo it back as "ungrounded".
        // Groupings 3-3-3-2 and 4-4-3, separated by space, dot or hyphen. Dates (2-2-4) never fit these shapes.
        new Rule("TCKN", Pattern.compile("(?<!\\d)(?:[1-9]\\d{2}[\\s.-]?\\d{3}[\\s.-]?\\d{3}[\\s.-]?\\d{2}|[1-9]\\d{3}[\\s.-]?\\d{4}[\\s.-]?\\d{3})(?!\\d)"), s -> true),
        new Rule("TELEFON", Pattern.compile("(?<!\\d)(?:\\+?90[\\s-]?|0)?\\(?5\\d{2}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}(?!\\d)"), s -> true),
        new Rule("EPOSTA", Pattern.compile("\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b"), s -> true));

    public static Masked mask(String input) {
        List<Entity> entities = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        // NFKC folds full-width digits and non-breaking spaces to their plain forms so the patterns below see them.
        String text = input == null ? "" : Normalizer.normalize(input, Normalizer.Form.NFKC);
        for (Rule rule : RULES) {
            Matcher m = rule.re().matcher(text);
            text = m.replaceAll(mr -> {
                String value = mr.group();
                if (!rule.ok().test(value)) return Matcher.quoteReplacement(value);
                int n = counters.merge(rule.type(), 1, Integer::sum);
                String placeholder = "[" + rule.type() + "_" + n + "]";
                entities.add(new Entity(rule.type(), placeholder, value));
                return Matcher.quoteReplacement(placeholder);
            });
        }
        return new Masked(text, entities);
    }

    /** "12345678946" -> "*********46": enough for a human reviewer to match, useless to a thief. */
    public static String display(String value) {
        if (value == null || value.length() < 4) return value == null ? null : "***";
        return "*".repeat(value.length() - 2) + value.substring(value.length() - 2);
    }
}
