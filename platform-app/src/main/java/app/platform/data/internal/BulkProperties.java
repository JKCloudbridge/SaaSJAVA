package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the bulk operations on records (Sprint 17, ADR-0122).
 *
 * @param maxRecords the most records one call can carry (default 200)
 * @param maxBodyBytes the largest request body of a bulk call, checked on the announced length before the body is read
 *        (default 8 MiB, which holds 200 records of an ordinary size)
 */
@ConfigurationProperties("platform.data.bulk")
record BulkProperties(@DefaultValue("200") int maxRecords, @DefaultValue("8388608") long maxBodyBytes) {

    BulkProperties {
        if (maxRecords < 1 || maxRecords > 1000 || maxBodyBytes < 1024 || maxBodyBytes > 67_108_864L) {
            throw new IllegalArgumentException("platform.data.bulk: a setting is outside its range");
        }
    }
}
