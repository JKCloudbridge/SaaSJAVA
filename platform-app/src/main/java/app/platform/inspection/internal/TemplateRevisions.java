package app.platform.inspection.internal;

import app.platform.sheets.SheetLifecycle;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The revision number people read for a version of a template (FA8, ADR-0202). The database gives a version its
 * revision
 * number when it first becomes the one in use (the next after the highest before it), so a copy that was rejected never
 * uses one and the numbers have no gaps. A version that was in use before numbers were stored has none, and is read as
 * its stored number minus one, the rule of Revision 00 for the first version (FA7). Pure apart from the one read.
 */
@Component
class TemplateRevisions {

    private final SheetLifecycle sheets;

    TemplateRevisions(SheetLifecycle sheets) {
        this.sheets = sheets;
    }

    /** The revision number of a version: its stored one, else the version number minus one. */
    int of(RecordSet set, UUID templateId, int versionNo) {
        return sheets.revisionOf(set.template(), templateId, versionNo).orElse(Math.max(0, versionNo - 1));
    }

    /** The revision as people read it: two digits at least. */
    static String show(int revision) {
        return String.format(Locale.ROOT, "%02d", Math.max(0, revision));
    }

    /** The revision of a version as people read it. */
    String showOf(RecordSet set, UUID templateId, int versionNo) {
        return show(of(set, templateId, versionNo));
    }
}
