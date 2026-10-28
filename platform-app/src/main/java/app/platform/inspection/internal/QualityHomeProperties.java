package app.platform.inspection.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the home page of the Quality Management System (FA9, ADR-0203). The page counts by reading the headers
 * the caller may see, one bounded page at a time; these limits keep one request from reading without end, and a number
 * that reached its limit says "at least".
 *
 * @param maxScanned the most records of one kind one request reads (50,000): templates, and the fill-ins sent in the
 *        period and the one before it
 * @param maxApprovals the most approvals one request looks at to work out the days at each step (500)
 * @param recentActivity how many things the activity list shows (10)
 */
@ConfigurationProperties("platform.inspection.home")
record QualityHomeProperties(@DefaultValue("50000") int maxScanned, @DefaultValue("500") int maxApprovals,
        @DefaultValue("10") int recentActivity) {

    QualityHomeProperties {
        range("max-scanned", maxScanned, 1, 1_000_000);
        range("max-approvals", maxApprovals, 1, 5_000);
        range("recent-activity", recentActivity, 1, 50);
    }

    /** The caps of the product, for tests and callers that need no setting. */
    static QualityHomeProperties standard() {
        return new QualityHomeProperties(50_000, 500, 10);
    }

    private static void range(String name, int value, int least, int most) {
        if (value < least || value > most) {
            throw new IllegalArgumentException("platform.inspection.home." + name + " must be " + least + " to "
                    + most);
        }
    }
}
