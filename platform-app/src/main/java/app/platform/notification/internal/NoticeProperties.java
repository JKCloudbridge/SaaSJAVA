package app.platform.notification.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the in-app notices (Sprint 25, ADR-0154). The defaults suit a deployment.
 *
 * @param pageSize the page size when none is asked for
 * @param maxPageSize the largest page a caller may ask for
 * @param retention how long notices stay
 */
@ConfigurationProperties("platform.notification.notices")
record NoticeProperties(@DefaultValue("30") int pageSize, @DefaultValue("100") int maxPageSize,
        @DefaultValue Retention retention) {

    NoticeProperties {
        if (pageSize < 1 || maxPageSize < pageSize || maxPageSize > 200) {
            throw new IllegalArgumentException("platform.notification.notices.* is out of range");
        }
    }

    /**
     * How long notices are kept (ADR-0154): read ones are removed after {@code readKeep}, unread ones after the longer
     * {@code unreadKeep}, and nothing younger than {@code floor} is ever removed, whatever the other two say.
     *
     * @param enabled whether this instance runs the clean-up
     * @param readKeep how long a read notice stays (default 30 days)
     * @param unreadKeep how long an unread notice stays (default 180 days)
     * @param floor the youngest age at which a notice may be removed at all (default 7 days)
     * @param interval how often the clean-up runs
     * @param batchSize how many notices one statement removes
     */
    record Retention(@DefaultValue("true") boolean enabled, @DefaultValue("30d") Duration readKeep,
            @DefaultValue("180d") Duration unreadKeep, @DefaultValue("7d") Duration floor,
            @DefaultValue("1h") Duration interval, @DefaultValue("1000") int batchSize) {

        Retention {
            if (floor.compareTo(Duration.ofDays(1)) < 0 || batchSize < 1 || batchSize > 10_000
                    || interval.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("platform.notification.notices.retention.* is out of range: the "
                        + "floor is at least a day");
            }
        }

        /** The age a read notice must reach before it may be removed: never younger than the floor. */
        Duration readCutoff() {
            return readKeep.compareTo(floor) < 0 ? floor : readKeep;
        }

        /** The age an unread notice must reach before it may be removed: never younger than the floor. */
        Duration unreadCutoff() {
            return unreadKeep.compareTo(floor) < 0 ? floor : unreadKeep;
        }
    }
}
