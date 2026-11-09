package dev.oktay.kyc.check;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic validators. The LLM never decides whether an identifier is valid: this code does. */
public final class Checks {
    private Checks() {}

    /** Official TCKN checksum, so a random 11-digit number is not treated as an identity number. */
    public static boolean validTckn(String raw) {
        if (raw == null) return false;
        String d = raw.replaceAll("\\D", "");
        if (!d.matches("[1-9]\\d{10}")) return false;
        int[] n = d.chars().map(c -> c - '0').toArray();
        int odd = n[0] + n[2] + n[4] + n[6] + n[8];
        int even = n[1] + n[3] + n[5] + n[7];
        int d10 = Math.floorMod(odd * 7 - even, 10);
        int d11 = Arrays.stream(n, 0, 10).sum() % 10;
        return n[9] == d10 && n[10] == d11;
    }

    /** Turkish IBAN: TR + 24 digits, ISO 7064 mod-97 check. */
    public static boolean validIban(String raw) {
        if (raw == null) return false;
        String s = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!s.matches("TR\\d{24}")) return false;
        String rearranged = s.substring(4) + s.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Lowercase, strip accents, map Turkish dotless i: "ŞÜKRÜ" and "sukru" compare equal. */
    public static String fold(String s) {
        if (s == null) return "";
        String t = s.replace('İ', 'i').replace('I', 'i').replace('ı', 'i').toLowerCase(Locale.ROOT);
        return Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    public static Set<String> nameTokens(String name) {
        Set<String> out = new TreeSet<>();
        for (String t : fold(name).split("[^a-z]+")) if (t.length() > 1) out.add(t);
        return out;
    }

    /** Same person if the token sets are equal, or the shorter one (at least two tokens) is contained in the longer. */
    public static boolean sameName(String a, String b) {
        Set<String> x = nameTokens(a);
        Set<String> y = nameTokens(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        Set<String> small = x.size() <= y.size() ? x : y;
        Set<String> big = small == x ? y : x;
        return small.size() >= 2 && big.containsAll(small);
    }
}
