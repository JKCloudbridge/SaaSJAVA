package app.platform.sharedkernel.solution;

import java.util.OptionalInt;

/**
 * What an organization chose for an application, or the application's own default (MC2B, ADR-0261). A solution file
 * declares which settings an application offers and what each is until an organization chooses; the module that owns
 * the solution files implements this contract and keeps the organizations' choices. A module that owns an
 * application's functions asks it, in the organization of the thread's tenant context, and never reads the choices
 * itself.
 *
 * <p>The meaning of a key belongs to the module that asks for it; this contract only carries a whole number. The
 * answer is read live in the caller's transaction, so a change applies from the next question. A value is the
 * organization's: callers must not log it.
 */
public interface ApplicationSettings {

    /**
     * The value in force for the organization of the thread.
     *
     * @param application the API name of the application
     * @param key the name of the setting
     * @return the organization's own value, else the default the application's file declares; empty when no file
     *         declares the setting for the application
     */
    OptionalInt value(String application, String key);
}
