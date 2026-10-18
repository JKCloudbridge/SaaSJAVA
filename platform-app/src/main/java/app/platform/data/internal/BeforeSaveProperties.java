package app.platform.data.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bounds of before-save automation (Sprint 25, ADR-0160). It runs inside the transaction of every save of an object
 * that has one, so its bounds are a safety property of the whole platform and not a convenience.
 *
 * @param enabled whether before-save automations run at all: the switch an operator uses if one ever misbehaves
 * @param maxPerObject the most automations that run for one save; the definitions are limited to the same number
 * @param timeBudget the most time all the automations of one save may take together, conditions included
 */
@ConfigurationProperties("platform.data.before-save")
record BeforeSaveProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10") int maxPerObject,
        @DefaultValue("500ms") Duration timeBudget) {

    BeforeSaveProperties {
        if (maxPerObject < 1 || maxPerObject > 50 || timeBudget.compareTo(Duration.ofMillis(10)) < 0
                || timeBudget.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("platform.data.before-save.* is out of range");
        }
    }
}
