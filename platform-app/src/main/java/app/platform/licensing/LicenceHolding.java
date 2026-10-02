package app.platform.licensing;

import java.util.Set;
import java.util.UUID;

/**
 * Which licences one member holds (ADR-0046): the type of the licence for their profile, every licence type they hold
 * for any purpose, and the access policies that have a licence of their own. An access policy that needs a licence
 * counts for the member while one of the types they hold is the type it needs.
 *
 * @param profileType the key of the licence type held for the profile, or null when none is held
 * @param types the keys of every licence type the member holds (the profile's and the policies')
 * @param policies the access policies for which the member holds a licence of their own
 */
public record LicenceHolding(String profileType, Set<String> types, Set<UUID> policies) {

    /** Copies what is given. */
    public LicenceHolding {
        types = Set.copyOf(types);
        policies = Set.copyOf(policies);
    }

    /** A member who holds nothing. */
    public static LicenceHolding none() {
        return new LicenceHolding(null, Set.of(), Set.of());
    }

    /** Whether the member holds a licence of the type, for whatever purpose. */
    public boolean holds(String type) {
        return type != null && types.contains(type);
    }
}
