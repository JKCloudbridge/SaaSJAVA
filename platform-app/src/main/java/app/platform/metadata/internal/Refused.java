package app.platform.metadata.internal;

import java.util.List;
import java.util.UUID;

/**
 * A publication (a change set, or a rollback) that cannot go ahead because of the problems it found. Thrown from inside
 * the transaction, so everything the attempt did is rolled back with it; {@link MetadataAdministration} records the
 * refusal after the transaction ended and tells the caller what is wrong, each problem naming what depends on it
 * (ADR-0065).
 */
final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient UUID actor;
    private final String kind;
    private final transient UUID changeSet;
    private final transient List<MetadataLifecycle.Problem> problems;

    Refused(UUID actor, String kind, UUID changeSet, List<MetadataLifecycle.Problem> problems) {
        super("The publication cannot go ahead");
        this.actor = actor;
        this.kind = kind;
        this.changeSet = changeSet;
        this.problems = List.copyOf(problems);
    }

    UUID actor() {
        return actor;
    }

    /** {@code CHANGE_SET} or {@code ROLLBACK}. */
    String kind() {
        return kind;
    }

    UUID changeSet() {
        return changeSet;
    }

    List<MetadataLifecycle.Problem> problems() {
        return problems;
    }
}
