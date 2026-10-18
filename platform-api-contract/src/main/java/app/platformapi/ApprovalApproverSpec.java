package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One rule that names who is asked in an approval step (Sprint 24, ADR-0146). A closed description: the kind says what
 * is
 * meant and the value is the one setting that kind takes. The people are worked out when the step becomes active, not
 * when the process is saved.
 *
 * @param kind {@code USER}, {@code PROFILE}, {@code ROLE}, {@code GROUP}, {@code OWNER}, {@code RECORD_USER} or
 *        {@code OWNER_ROLE_MANAGER}; the catalogue says what each means and what its value names
 * @param value for {@code USER}, {@code PROFILE}, {@code ROLE} and {@code GROUP} the identifier of that person,
 * profile,
 *        role or public group; for {@code RECORD_USER} the API name of a user field of the object; left out for
 *        {@code OWNER} and {@code OWNER_ROLE_MANAGER}
 */
public record ApprovalApproverSpec(@NotBlank @Size(max = 30) String kind, @Size(max = 60) String value) {

    @Override
    public String toString() {
        return "ApprovalApproverSpec[" + kind + "]";
    }
}
