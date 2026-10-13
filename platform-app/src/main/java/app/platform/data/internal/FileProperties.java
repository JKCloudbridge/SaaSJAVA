package app.platform.data.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the files attached to records (Sprint 17, ADR-0120, ADR-0121).
 *
 * @param maxBytes the largest file in bytes (default 25 MiB)
 * @param maxPerRecord the most files one record can have (default 20), pending uploads included
 * @param deniedTypes content types that are refused whatever the file is called (executables, scripts and the types a
 *        browser would run); compared in lower case, without parameters
 * @param deniedExtensions endings of names that are refused for the same reason
 * @param allowedTypes when not empty, the only content types accepted (a deployment that wants a short list)
 * @param pendingTtl how long an upload may stay unfinished before its row and bytes are removed (default one hour)
 * @param cleanup how the removal of bytes runs
 */
@ConfigurationProperties("platform.data.files")
record FileProperties(@DefaultValue("26214400") long maxBytes, @DefaultValue("20") int maxPerRecord,
        @DefaultValue({"application/x-msdownload", "application/x-msdos-program", "application/x-dosexec",
            "application/vnd.microsoft.portable-executable", "application/x-executable", "application/x-sh",
            "application/x-csh", "application/x-bat", "application/java-archive", "application/x-httpd-php",
            "application/javascript", "application/x-javascript", "text/javascript", "text/html",
            "application/xhtml+xml", "image/svg+xml", "application/x-shockwave-flash", "application/hta"})
        List<String> deniedTypes,
        @DefaultValue({"exe", "dll", "bat", "cmd", "com", "msi", "scr", "pif", "sh", "csh", "js", "mjs", "vbs",
            "vbe", "wsf", "ps1", "jar", "php", "html", "htm", "xhtml", "svg", "hta", "lnk", "reg", "apk"})
        List<String> deniedExtensions,
        @DefaultValue List<String> allowedTypes, @DefaultValue("1h") Duration pendingTtl,
        @DefaultValue Cleanup cleanup) {

    FileProperties {
        if (maxBytes < 1 || maxBytes > 5_368_709_120L || maxPerRecord < 1 || maxPerRecord > 1000
                || pendingTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("platform.data.files: a setting is outside its range");
        }
        deniedTypes = List.copyOf(deniedTypes);
        deniedExtensions = List.copyOf(deniedExtensions);
        allowedTypes = List.copyOf(allowedTypes);
    }

    /**
     * The removal of bytes and of unfinished uploads.
     *
     * @param enabled whether this instance does the work (two instances doing it at once is harmless)
     * @param interval how often a pass runs (default one minute)
     * @param batchSize the most keys one pass removes per organization (default 200)
     */
    record Cleanup(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration interval,
            @DefaultValue("200") int batchSize) {

        Cleanup {
            if (interval.compareTo(Duration.ofSeconds(1)) < 0 || batchSize < 1 || batchSize > 5_000) {
                throw new IllegalArgumentException("platform.data.files.cleanup: a setting is outside its range");
            }
        }
    }
}
