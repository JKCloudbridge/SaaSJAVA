package app.platform.notification.internal;

import app.platform.sharedkernel.notice.NoticeKind;
import java.util.Optional;

/**
 * Who wants what (Sprint 25, ADR-0156): the one function that turns a person's explicit choice and the platform's floor
 * into an answer. Pure logic, so the floor is proved without a database.
 *
 * <p>A person has a switch per category and channel. A switch they never touched is on. The platform fixes some on: the
 * whole account category, and any kind whose floor is set. A fixed switch ignores whatever is stored, so a row written
 * by a bug or by hand can never silence a notice the platform requires.
 */
final class PreferenceRules {

    /** The ways a person is told. */
    enum Channel {
        /** The bell and the list in the application. */
        IN_APP("In the application"),
        /** A mail to the person's address. */
        MAIL("By mail");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        static Optional<Channel> fromCode(String code) {
            for (Channel channel : values()) {
                if (channel.name().equals(code)) {
                    return Optional.of(channel);
                }
            }
            return Optional.empty();
        }
    }

    private PreferenceRules() {
    }

    /** Whether a switch is fixed on by the platform, so that a person cannot change it. */
    static boolean locked(NoticeKind.Category category) {
        return category == NoticeKind.Category.ACCOUNT;
    }

    /** Whether a kind of notice cannot be switched off, whatever its category. */
    static boolean locked(NoticeKind kind) {
        return kind.floor() || locked(kind.category());
    }

    /**
     * Whether the person wants a notice of this category on this channel.
     *
     * @param category the category
     * @param explicit the person's stored choice, or null when they never touched the switch
     * @return true unless the person switched it off and the platform does not fix it on
     */
    static boolean wanted(NoticeKind.Category category, Boolean explicit) {
        return locked(category) || explicit == null || explicit;
    }

    /** Whether the person wants a notice of this kind, with the floor of the kind taken into account. */
    static boolean wanted(NoticeKind kind, Boolean explicit) {
        return locked(kind) || explicit == null || explicit;
    }
}
