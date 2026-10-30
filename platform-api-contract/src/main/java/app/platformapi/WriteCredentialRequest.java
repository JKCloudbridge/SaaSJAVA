package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The value of a credential, on its way in (Sprint 27, ADR-0213). It travels in the body of a request and nowhere else:
 * never in an address, never echoed, never logged, never audited. It prints as redacted. The value is not trimmed or
 * changed; what the person typed is what is sealed.
 *
 * @param value the secret, between 1 and 4096 characters
 */
public record WriteCredentialRequest(@NotNull @Size(min = 1, max = 4096) String value) {

    @Override
    public String toString() {
        return "WriteCredentialRequest[redacted]";
    }
}
