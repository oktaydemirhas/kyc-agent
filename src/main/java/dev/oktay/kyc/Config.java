package dev.oktay.kyc;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Config {
    /** Injected so the date rules (expiry, age, 90-day address proof) are testable with a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Europe/Istanbul"));
    }
}
