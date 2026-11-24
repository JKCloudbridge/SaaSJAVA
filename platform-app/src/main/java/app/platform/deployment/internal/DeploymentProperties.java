package app.platform.deployment.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the deployment behaviour (Sprint 33, ADR-0286 to ADR-0291). Every default keeps a developer machine and
 * the tests exactly as they were; the {@code prod} profile and the deployment's environment variables turn the rest on.
 *
 * @param shutdownDelay how long a stopping instance keeps accepting requests after it has said it is not ready, so a
 *        load balancer that learns of it by health check or by deregistration stops sending before the server stops
 *        listening (default zero: nothing waits on a developer machine; {@code prod} sets ten seconds)
 * @param build which release is running; set by the image build and only ever shown, never a secret
 * @param forwardedForHops how many trusted proxies each appended an entry to {@code X-Forwarded-For}; the entry that
 *        many places from the right is the caller's address and replaces the header ({@link ForwardedForNormalizer});
 *        0 (the default) changes nothing, {@code prod} sets 1 (a load balancer that appends)
 */
@ConfigurationProperties("platform.deployment")
record DeploymentProperties(@DefaultValue("0s") Duration shutdownDelay, @DefaultValue Build build,
        @DefaultValue("0") int forwardedForHops) {

    /**
     * The stamp of the release.
     *
     * @param commit the identifier of the source revision (hexadecimal) or {@code unknown}
     * @param time when the image was built (an instant) or {@code unknown}
     */
    record Build(@DefaultValue("unknown") String commit, @DefaultValue("unknown") String time) {
    }
}
