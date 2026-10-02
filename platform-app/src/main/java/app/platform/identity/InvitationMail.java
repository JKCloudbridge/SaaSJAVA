package app.platform.identity;

/**
 * What an invitation mail needs.
 *
 * @param organizationName the organization's name as its founder typed it: text chosen by a person, so the mail text
 *        bounds and escapes it and puts no link in it
 * @param email the invited address
 * @param existingAccount whether the address already has an account: the mail then says "sign in and accept" instead of
 *        "choose a password"
 * @param firstAdministrator whether a platform administrator invited the person to set up and administer the
 *        organization (Sprint 6, ADR-0037): the words then say so instead of "an administrator invited you"
 */
public record InvitationMail(String organizationName, String email, boolean existingAccount,
        boolean firstAdministrator) {

    /** An invitation made by an organization's own administrators. */
    public InvitationMail(String organizationName, String email, boolean existingAccount) {
        this(organizationName, email, existingAccount, false);
    }

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints this record.
        return "InvitationMail[redacted]";
    }
}
