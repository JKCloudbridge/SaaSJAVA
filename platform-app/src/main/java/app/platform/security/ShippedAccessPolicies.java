package app.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The access policies the platform ships with a solution (Functional track FA1, ADR-0175, ADR-0181). They are rows
 * of the organization, made the way the system profiles are, because a policy is assigned to people and referenced
 * by member rows: it is the organization's data, where an application is only a view. They are marked as shipped,
 * so an administrator can give them to members and groups and can neither change nor remove them; the database
 * enforces it.
 *
 * <p>Every method works for the organization of the thread's tenant context and never opens another one. Making the
 * policies is idempotent: a policy that exists is found by its key, whatever its name became in the meantime, and is
 * only brought up to date when what the platform declares for it changed. A policy is never removed (an organization
 * that loses the solution's plan feature keeps its policies; they name objects that are then hidden).
 */
public interface ShippedAccessPolicies {

    /**
     * What the platform declares for one shipped policy.
     *
     * @param key its stable identity inside the owner, for example {@code demo.reader}: lower case letters,
     *        digits, dots and hyphens
     * @param name the name people read; if an administrator's policy already has it, a suffix is added
     * @param description what it is for
     * @param objects the rights it gives on objects, by object key; it gives no ability and no right on fields yet
     */
    record Declaration(String key, String name, String description, Map<String, Set<ObjectAction>> objects) {

        /** Copies what is given, in a stable order. */
        public Declaration {
            Map<String, Set<ObjectAction>> copy = new TreeMap<>();
            objects.forEach((object, actions) -> copy.put(object, Set.copyOf(new TreeSet<>(actions))));
            objects = Map.copyOf(copy);
        }

        /**
         * A fingerprint of everything the declaration says, so a change of any of it is noticed with one
         * comparison.
         */
        public String fingerprint() {
            StringBuilder text = new StringBuilder(key).append('\n').append(name).append('\n').append(description);
            new TreeMap<>(objects).forEach((object, actions) -> {
                text.append('\n').append(object).append(':');
                new TreeSet<>(actions).forEach(action -> text.append(action.key()).append(','));
            });
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
            }
        }

        /** Prints the key only. */
        @Override
        public String toString() {
            return "Declaration[" + key + "]";
        }
    }

    /**
     * What making the policies did.
     *
     * @param made how many policies were new
     * @param updated how many existed and were brought up to date
     */
    record Result(int made, int updated) {
    }

    /** Whether the organization already has every declared policy, as declared. A read; it writes nothing. */
    boolean upToDate(String owner, List<Declaration> declared);

    /**
     * Makes the declared policies the organization lacks and brings the others up to date, in a transaction of its own
     * so it can be asked from a request that only reads. Safe to call at the same moment from several requests and
     * several instances: they wait for one another and the second finds nothing to do.
     */
    Result ensure(String owner, List<Declaration> declared);
}
