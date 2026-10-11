package app.platform.expression;

/**
 * One function of the library, described for people: the guide and the setup screens show this list, so that no screen
 * holds a list of its own.
 *
 * @param name the name, in capitals
 * @param signature how a call is written, for example {@code ROUND(number, places)}
 * @param description what it does, in plain words
 * @param rulesOnly whether only a validation rule may use it (it reads the moment of the save)
 */
public record FunctionInfo(String name, String signature, String description, boolean rulesOnly) {
}
