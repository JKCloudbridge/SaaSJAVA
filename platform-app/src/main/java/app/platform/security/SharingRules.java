package app.platform.security;

import java.util.Map;
import java.util.Set;

/**
 * What the sharing rules depend on, for the metadata lifecycle's dependency graph (Sprint 17, ADR-0116): a field that a
 * criteria-based rule names cannot be removed until the rule is changed. The answer is read from the current rows in
 * the caller's transaction, so a check that runs on the result of a change set (inside the transaction that is rolled
 * back) sees the rules as they are now.
 */
public interface SharingRules {

    /**
     * The fields that the criteria-based rules name, by object and by the name of the rule. Rules that name no field
     * (owner and group rules) are left out.
     */
    Map<String, Map<String, Set<String>>> criterionFields();
}
