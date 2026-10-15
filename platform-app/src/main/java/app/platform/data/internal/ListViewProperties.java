package app.platform.data.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the saved list views (Sprint 19, ADR-0130).
 *
 * @param maxPersonalPerObject the most personal views one person can keep for one object (default 50)
 * @param maxSharedPerObject the most shared views one object can have (default 50): a list of views a person has to
 *        scroll is not a way to find a list
 */
@ConfigurationProperties("platform.data.views")
record ListViewProperties(@DefaultValue("50") int maxPersonalPerObject, @DefaultValue("50") int maxSharedPerObject) {

    ListViewProperties {
        if (maxPersonalPerObject < 1 || maxPersonalPerObject > 1000) {
            throw new IllegalArgumentException("platform.data.views.max-personal-per-object must be 1 to 1000");
        }
        if (maxSharedPerObject < 1 || maxSharedPerObject > 1000) {
            throw new IllegalArgumentException("platform.data.views.max-shared-per-object must be 1 to 1000");
        }
    }
}
