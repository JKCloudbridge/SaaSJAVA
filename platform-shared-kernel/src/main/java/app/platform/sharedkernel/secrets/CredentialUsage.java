package app.platform.sharedkernel.secrets;

/**
 * Who uses a credential, asked by the secrets store and answered by the module that holds the users (the connections of
 * the integration module). It exists so that the store never reads another module's table and the integration module
 * never depends on the store's implementation (ADR-0213).
 *
 * <p>Every call is made for the organization of the current tenant context and inside the transaction of the change
 * that raised it. A credential is identified by its name inside the organization.
 */
public interface CredentialUsage {

    /** How many live users of the credential there are. */
    int usedBy(String name);

    /**
     * Whether a user that is switched on depends on the credential. Removing a credential in that state is refused;
     * a rotation is not.
     */
    boolean inUseWhileOn(String name);

    /**
     * The credential was rotated or removed: whatever vouched for it (the last successful test of a connection) no
     * longer does. Switched-on users keep running; the next use or test shows whether the new value works.
     *
     * @return how many users were marked
     */
    int voidTests(String name);
}
