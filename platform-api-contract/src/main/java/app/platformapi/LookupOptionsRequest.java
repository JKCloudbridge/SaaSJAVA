package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Asks which records a link field could point to (Sprint 19, ADR-0129). The text is part of a name a person typed, so
 * it travels in the body, never in the address (an address is written into the logs of every proxy on the way), and
 * the request never prints.
 *
 * @param text part of the name to look for (case does not matter); left out or empty, the first records in name order
 * @param limit how many to return at most, 1 to 25; left out, 10
 */
public record LookupOptionsRequest(@Size(max = 200) String text, @Min(0) @Max(100) Integer limit) {

    @Override
    public String toString() {
        return "LookupOptionsRequest[redacted]";
    }
}
