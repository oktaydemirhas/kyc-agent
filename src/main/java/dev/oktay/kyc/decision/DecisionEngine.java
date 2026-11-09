package dev.oktay.kyc.decision;

import dev.oktay.kyc.check.Checks;
import dev.oktay.kyc.extract.Extracted;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The only place that decides. The model extracts fields; these rules turn them into APPROVE / MANUAL_REVIEW / REJECT.
 * When anything is doubtful the answer is a human, never a silent approval.
 */
@Component
public class DecisionEngine {
    public enum Outcome { APPROVE, MANUAL_REVIEW, REJECT }

    public record Reason(String code, String message, Outcome severity) {}

    public record Decision(Outcome outcome, List<Reason> reasons) {}

    private static final int ADDRESS_PROOF_MAX_DAYS = 90;
    private final Clock clock;

    public DecisionEngine(Clock clock) {
        this.clock = clock;
    }

    public Decision decide(List<Extracted> docs) {
        List<Reason> r = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        for (Extracted d : docs) {
            if (d.injection()) r.add(review("INJECTION_SUSPECTED", "Belge, sistemi yönlendirmeye çalışan bir ifade içeriyor."));
            for (String p : d.problems()) r.add(review("EXTRACTION_UNRELIABLE", "Alan çıkarımı doğrulanamadı: " + p));
            if (!d.declaredType().equalsIgnoreCase(d.docType())) r.add(review("TYPE_MISMATCH", "Beyan edilen belge türü ile içerik uyuşmuyor."));
        }

        Extracted id = docs.stream().filter(d -> "KIMLIK".equals(d.docType())).findFirst().orElse(null);
        if (id == null) {
            r.add(review("MISSING_ID_DOCUMENT", "Kimlik belgesi bulunamadı."));
        } else {
            if (id.tckn() == null || id.fullName() == null || id.birthDate() == null || id.expiryDate() == null) {
                r.add(review("ID_FIELDS_MISSING", "Kimlikte zorunlu alanlardan biri okunamadı."));
            }
            if (id.tckn() != null && !Checks.validTckn(id.tckn())) r.add(reject("TCKN_INVALID", "TC kimlik numarası sağlama kontrolünden geçmedi."));
            if (id.expiryDate() != null && id.expiryDate().isBefore(today)) r.add(reject("ID_EXPIRED", "Kimliğin geçerlilik süresi dolmuş."));
            if (id.birthDate() != null && Period.between(id.birthDate(), today).getYears() < 18) r.add(reject("UNDERAGE", "Başvuran 18 yaşından küçük."));

            for (Extracted d : docs) {
                if (d == id) continue;
                if (d.fullName() != null && id.fullName() != null && !Checks.sameName(id.fullName(), d.fullName())) {
                    r.add(review("NAME_MISMATCH", "Ad soyad, belgeler arasında uyuşmuyor (" + d.docType() + ")."));
                }
                if (d.tckn() != null && id.tckn() != null && !d.tckn().equals(id.tckn())) {
                    r.add(review("TCKN_MISMATCH", "TC kimlik numarası belgeler arasında uyuşmuyor (" + d.docType() + ")."));
                }
            }
        }

        for (Extracted d : docs) {
            if ("ADRES".equals(d.docType())) {
                if (d.issueDate() == null) r.add(review("ADDRESS_DATE_MISSING", "Adres belgesinin tarihi okunamadı."));
                else if (ChronoUnit.DAYS.between(d.issueDate(), today) > ADDRESS_PROOF_MAX_DAYS) r.add(review("ADDRESS_PROOF_OLD", "Adres belgesi 90 günden eski."));
                else if (d.issueDate().isAfter(today)) r.add(review("ADDRESS_DATE_FUTURE", "Adres belgesinin tarihi gelecekte."));
            }
            if ("BANKA".equals(d.docType()) && !Checks.validIban(d.iban())) r.add(review("IBAN_INVALID", "IBAN eksik veya geçersiz."));
        }

        Outcome outcome = r.stream().anyMatch(x -> x.severity() == Outcome.REJECT) ? Outcome.REJECT
                : r.isEmpty() ? Outcome.APPROVE : Outcome.MANUAL_REVIEW;
        return new Decision(outcome, r);
    }

    private static Reason review(String code, String msg) {
        return new Reason(code, msg, Outcome.MANUAL_REVIEW);
    }

    private static Reason reject(String code, String msg) {
        return new Reason(code, msg, Outcome.REJECT);
    }
}
