package dev.oktay.kyc.check;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Known instruction-injection patterns, matched on accent-folded text. A document is data: a sentence that
 * tries to instruct the system is removed before the model sees it and raised as a risk signal.
 * This is a denylist, so it only catches known phrasings; the decision engine never takes the model's word anyway.
 */
public final class Injection {
    private Injection() {}

    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("onceki.{0,20}(talimat|kural|komut|yonerge)"),
        Pattern.compile("(talimat|kural|komut)lar?i?.{0,15}(unut|yok say|gormezden)"),
        Pattern.compile("ignore.{0,25}(previous|prior|above|all).{0,25}(instruction|rule|prompt)"),
        Pattern.compile("(sistem|system).{0,10}(prompt|mesaj|talimat)"),
        Pattern.compile("(onayla|onaylayin|approve|accept).{0,40}(musteri|basvuru|customer|application|kyc|hesap)"),
        Pattern.compile("(kyc|dogrulama|verification).{0,20}(gec|atla|bypass|skip)"),
        Pattern.compile("ai asistan|yapay zeka asistan|language model|you are now"));

    public static boolean detect(String sentence) {
        String f = Checks.fold(sentence);
        return PATTERNS.stream().anyMatch(p -> p.matcher(f).find());
    }

    public record Result(String text, List<String> removed) {}

    public static Result strip(String input) {
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String part : input.split("(?<=[.!?])\\s+|\\R+")) {
            if (part.isBlank()) continue;
            (detect(part) ? removed : kept).add(part.strip());
        }
        return new Result(String.join("\n", kept), removed);
    }
}
