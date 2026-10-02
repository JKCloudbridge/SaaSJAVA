package app.platform.licensing;

/**
 * A kind of licence an organization can hold a pool of (ADR-0031). Data, not a fixed list in the code: the first two
 * are {@code user} and {@code admin}.
 *
 * @param key the stable lower-case key
 * @param name the name for people
 * @param kind {@code SEAT}: the right to occupy a seat, the only kind a profile can belong to; {@code ADD_ON}: sold on
 *        top, for example the licence of a standard access policy (ADR-0046)
 */
public record LicenceTypeView(String key, String name, String kind) {

    /** The kind of a licence type that a profile can belong to. */
    public static final String SEAT = "SEAT";

    /** The kind of a licence type sold on top of the seats. */
    public static final String ADD_ON = "ADD_ON";

    /** Whether a profile can belong to this licence type. */
    public boolean seat() {
        return SEAT.equals(kind);
    }
}
