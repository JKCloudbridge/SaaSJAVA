package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the explicit sharing of single records (Sprint 17, ADR-0117).
 *
 * @param maxSharesPerRecord the most shares one record can have (default 100); a record that many people need is
 *        shared through a public group or a rule instead
 */
@ConfigurationProperties("platform.data.sharing")
record SharingProperties(@DefaultValue("100") int maxSharesPerRecord) {

    SharingProperties {
        if (maxSharesPerRecord < 1 || maxSharesPerRecord > 10_000) {
            throw new IllegalArgumentException("platform.data.sharing.max-shares-per-record must be 1 to 10000");
        }
    }
}
