package app.platform.architecture.fixtures;

import app.platform.data.RecordShares;
import app.platform.security.Grantee;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.SheetLifecycle;
import java.util.UUID;

/**
 * A class of the kind the architecture rules of the template lifecycle exist to keep out (FA4, ADR-0188): outside the
 * module of the cluster, it shares a record from Java and moves a version of a sheet. It is never used. The
 * architecture tests import it on purpose, to prove that their rules fail for it; the rules that guard the build do
 * not import test classes.
 */
public final class ClusterLikeSharer {

    private final RecordShares shares;
    private final SheetLifecycle sheets;
    private final FillLifecycle fills;

    public ClusterLikeSharer(RecordShares shares, SheetLifecycle sheets, FillLifecycle fills) {
        this.shares = shares;
        this.sheets = sheets;
        this.fills = fills;
    }

    /** Sends a fill-in for review without the approval engine's say (FF1, ADR-0192). */
    public void send(String object, UUID fill, UUID request, UUID actor) {
        fills.submit(object, fill, request, actor);
    }

    /** Gives a group read access to a record and makes a version the one in use, without anyone's say. */
    public void publish(String object, UUID record, UUID group, UUID actor) {
        shares.grantRead(object, record, new Grantee(Grantee.Kind.GROUP, group), actor);
        sheets.publish(object, record, 1, actor);
    }
}
