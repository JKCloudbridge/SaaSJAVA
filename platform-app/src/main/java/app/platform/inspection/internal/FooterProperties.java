package app.platform.inspection.internal;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the footer of a sheet (MC1, ADR-0257). The screen writes the day of a Revision's publish in the reader's
 * own time zone; the server does not know that zone, so the file and the print of a download use this one.
 *
 * @param zone the time zone in which the file's footer day is written (a region name such as {@code UTC} or a city
 *        zone); default {@code UTC}
 */
@ConfigurationProperties("platform.inspection.footer")
record FooterProperties(@DefaultValue("UTC") String zone) {

    FooterProperties {
        try {
            ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new IllegalArgumentException("platform.inspection.footer.zone must be a time zone name");
        }
    }

    /** The zone of the footer day. */
    ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
