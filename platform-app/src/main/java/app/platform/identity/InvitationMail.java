package app.platform.identity;

/**
 * What an invitation mail needs.
 *
 * @param organizationName the organization's name as its founder typed it: text chosen by a person, so the mail text
 *        bounds and escapes it and puts no link in it
 * @param email the invited address
 * @param existingAccount whether the address already has an account: the mail then says "sign in and accept" instead of
 *        "choose a password"
 */
public record InvitationMail(String organizationName, String email, boolean existingAccount) {

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints this record.
        return "InvitationMail[redacted]";
    }
}
