package app.platform.metadata.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How large the rule of a dependent picklist may be (FA2A, ADR-0177): the most values of the controlling field it may
 * name and the most pairs it may list. Settings under {@code platform.metadata.limits}, read here by name so that the
 * other limits of the module keep their shape.
 */
@Component
final class PicklistDependencyLimits {

    private final int controllingValues;
    private final int pairs;

    PicklistDependencyLimits(
            @Value("${platform.metadata.limits.max-dependency-controlling-values:200}") int controllingValues,
            @Value("${platform.metadata.limits.max-dependency-pairs:5000}") int pairs) {
        if (controllingValues < 1 || pairs < 1) {
            throw new IllegalArgumentException("platform.metadata.limits.max-dependency-* must be at least 1");
        }
        this.controllingValues = controllingValues;
        this.pairs = pairs;
    }

    /** The most values of the controlling field one rule may name. */
    int controllingValues() {
        return controllingValues;
    }

    /** The most pairs one rule may list. */
    int pairs() {
        return pairs;
    }
}
