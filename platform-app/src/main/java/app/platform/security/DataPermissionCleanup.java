package app.platform.security;

import app.platform.sharedkernel.ActorId;

/**
 * Ends the permissions on an object or a field that no longer exists (ADR-0062). The security module stores permissions
 * by API name and never owns objects (ADR-0049); when the metadata module removes an object or a field it calls this in
 * the same transaction, so that an object made again later under the same name never wakes up the permissions of the
 * old one. A permission for a name the catalogue does not know is already ignored when permissions are computed; this
 * removes the stale rows as well, which is what makes a reused name safe.
 *
 * <p>Answers are for the organization of the thread's tenant context and run in the caller's transaction.
 */
public interface DataPermissionCleanup {

    /**
     * Ends every permission on the object and on all its fields, in profiles, access policies and individual grants.
     *
     * @return how many permission lines were ended
     */
    int forgetObject(String objectApiName, ActorId actor);

    /**
     * Ends every permission on one field.
     *
     * @return how many permission lines were ended
     */
    int forgetField(String objectApiName, String fieldApiName, ActorId actor);
}
