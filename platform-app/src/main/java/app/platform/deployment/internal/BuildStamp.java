package app.platform.deployment.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;

/**
 * Says which release is running (ADR-0287): in {@code /actuator/info} on the management port and in one line of the
 * log at start. The commit and the build time are given to the image build; only a hexadecimal commit and an instant
 * are accepted, anything else shows as {@code unknown}, so a value that does not look like a stamp can never put text
 * of someone's choosing into a log line. Nothing here is a secret and nothing identifies a person or an organization.
 */
final class BuildStamp implements InfoContributor, ApplicationListener<ApplicationReadyEvent> {

    static final String UNKNOWN = "unknown";

    private static final Logger LOG = LoggerFactory.getLogger(BuildStamp.class);
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.\\-+]{1,40}");

    private final String commit;
    private final String builtAt;
    private final String version;

    BuildStamp(DeploymentProperties.Build build, BuildProperties properties) {
        this.commit = accept(build.commit(), COMMIT);
        this.builtAt = accept(build.time(), INSTANT);
        this.version = properties == null ? UNKNOWN : accept(properties.getVersion(), VERSION);
    }

    static String accept(String value, Pattern shape) {
        return value != null && shape.matcher(value).matches() ? value : UNKNOWN;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("version", version);
        release.put("commit", commit);
        release.put("builtAt", builtAt);
        builder.withDetail("release", release);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        LOG.info("The platform is running: version {}, commit {}, built {}", version, commit, builtAt);
    }

    String commit() {
        return commit;
    }
}
