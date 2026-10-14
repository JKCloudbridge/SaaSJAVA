package app.platform.security;

/**
 * Which records of one object a member may read, edit and delete, as three {@link Admission}s (ADR-0115). It is what
 * {@link RecordSecurity#accessTo} answers; the data module reads it for every list, query and single read, and
 * {@link Decisions} asks it for one record.
 *
 * <p>It does not say whether the member may use the object at all (that is {@link ObjectAction}, answered by
 * {@link Decisions#accessTo}); a member without the permission to read the object has {@code Nothing} everywhere here
 * too, so a caller that forgets the first question still gets the safe answer. The edit admission is contained in the
 * read admission and the delete admission in the edit admission.
 *
 * @param object the object key
 * @param read the records the member may read
 * @param edit the records the member may change
 * @param delete the records the member may delete
 * @param truncated whether a set of owners was cut at the bound of {@code platform.security.records.max-owners}: the
 *        admissions then admit fewer records than the rules say, never more (ADR-0115, point 3)
 */
public record RecordAccess(String object, Admission read, Admission edit, Admission delete, boolean truncated) {

    /** No record at all. */
    public static RecordAccess nothing(String object) {
        return new RecordAccess(object, Admission.nothing(), Admission.nothing(), Admission.nothing(), false);
    }

    /** Every record, for every action. */
    public static RecordAccess everything(String object) {
        return new RecordAccess(object, Admission.everything(), Admission.everything(), Admission.everything(), false);
    }

    /** The admission for the action. */
    public Admission forAction(RecordAction action) {
        return switch (action) {
            case READ -> read;
            case EDIT -> edit;
            case DELETE -> delete;
        };
    }

    /** Whether every action admits every record: no condition is needed in any statement. */
    public boolean unrestricted() {
        return read instanceof Admission.Everything && edit instanceof Admission.Everything
                && delete instanceof Admission.Everything;
    }
}
