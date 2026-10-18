package app.platform.platformadmin.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the end of a trial does, and when (Sprint 25, ADR-0162): a pure function of the end of the trial, the settings
 * and what already happened. No database, no clock and no I/O, so every rule is proved without a container.
 *
 * <p>The organization keeps working for the <em>grace period</em> after the trial ends. The administrators are told
 * twice before it is suspended (by default 7 days and 1 day before) and once when it is. The suspension is the
 * lifecycle's own: sessions end, the data stays, and a platform administrator reinstates the organization when a plan
 * is active. Nothing here ever deletes anything.
 *
 * <p>A notice is never earlier than the end of the trial (a short grace period does not warn before the trial is over).
 * When the job was down and several stages fell due together, only the latest is acted on: the earlier ones are noted
 * as passed so they do not come back, and nobody is sent three mails at once.
 */
final class TrialEndPolicy {

    /** The stages, in the order they happen. The names are those of the marker table. */
    enum Stage {
        /** The first warning, the first number of days before the suspension. */
        NOTICE_FIRST,
        /** The last warning, the second number of days before the suspension. */
        NOTICE_FINAL,
        /** The organization is suspended. */
        SUSPENDED
    }

    /**
     * The settings.
     *
     * @param grace how long the organization works after the trial ends
     * @param firstNoticeDays how many days before the suspension the first warning comes
     * @param finalNoticeDays how many days before the suspension the last warning comes
     */
    record Settings(Duration grace, int firstNoticeDays, int finalNoticeDays) {

        Settings {
            if (grace.isNegative() || firstNoticeDays < 1 || finalNoticeDays < 1 || finalNoticeDays > firstNoticeDays) {
                throw new IllegalArgumentException("The trial settings are out of range");
            }
        }
    }

    private TrialEndPolicy() {
    }

    /** The moment the organization is suspended. */
    static Instant suspendAt(Instant trialEndsAt, Settings settings) {
        return trialEndsAt.plus(settings.grace());
    }

    /** The moment a stage falls due. */
    static Instant at(Stage stage, Instant trialEndsAt, Settings settings) {
        Instant suspend = suspendAt(trialEndsAt, settings);
        Instant notice = switch (stage) {
            case SUSPENDED -> suspend;
            case NOTICE_FIRST -> suspend.minus(Duration.ofDays(settings.firstNoticeDays()));
            case NOTICE_FINAL -> suspend.minus(Duration.ofDays(settings.finalNoticeDays()));
        };
        // A warning never comes before the trial is over.
        return stage == Stage.SUSPENDED || !notice.isBefore(trialEndsAt) ? notice : trialEndsAt;
    }

    /** The stages that are due at {@code now} and have not happened, in order. */
    static List<Stage> due(Instant now, Instant trialEndsAt, Settings settings, Set<String> done) {
        List<Stage> due = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (!done.contains(stage.name()) && !at(stage, trialEndsAt, settings).isAfter(now)) {
                due.add(stage);
            }
        }
        return due;
    }

    /** Of the stages that are due, the one to act on: the latest. The others are only noted. */
    static Optional<Stage> toAct(List<Stage> due) {
        return due.isEmpty() ? Optional.empty() : Optional.of(due.get(due.size() - 1));
    }

    /** How many whole days are left before the suspension, rounded up, at least one; for the words of a notice. */
    static int daysLeft(Instant now, Instant trialEndsAt, Settings settings) {
        long hours = Duration.between(now, suspendAt(trialEndsAt, settings)).toHours();
        return (int) Math.max(1, (hours + 23) / 24);
    }
}
