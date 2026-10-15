package app.platformapi;

import jakarta.validation.constraints.Pattern;

/**
 * Asks for the access token of a custom page's script (Sprint 18, ADR-0127).
 *
 * @param customPage the API name of the page the token is for, written to the audit trail; optional. It limits
 *        nothing: the token is for the signed-in member in this organization whatever page asks.
 */
public record EmbeddedTokenRequest(@Pattern(regexp = "^[A-Z][A-Za-z0-9_]{0,55}__c$") String customPage) {
}
