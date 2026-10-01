package app.platform.identity.internal;

/**
 * Knows passwords that must not be used because they are published in lists of common or breached passwords (story
 * S3-SEC-03). A seam on purpose: Sprint 3 ships {@link BundledCommonPasswords}; a larger list or a range query against
 * a breach service replaces it by providing another bean, without touching the policy.
 */
interface BreachedPasswords {

    /** Whether the password (as typed) is on the list. Case does not matter. */
    boolean contains(String password);
}
