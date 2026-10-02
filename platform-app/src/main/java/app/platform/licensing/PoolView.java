package app.platform.licensing;

/**
 * The numbers of one licence pool of an organization (ADR-0032).
 *
 * @param licenceType the licence type key
 * @param name the licence type's name for people
 * @param quantity how many licences the organization holds
 * @param assigned how many are held by members
 */
public record PoolView(String licenceType, String name, int quantity, int assigned) {

    /** How many are still free. */
    public int available() {
        return quantity - assigned;
    }
}
