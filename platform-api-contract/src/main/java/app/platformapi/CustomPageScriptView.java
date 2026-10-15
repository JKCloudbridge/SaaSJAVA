package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * What a member's browser needs to show a custom page (Sprint 18, ADR-0127): the script and the facts that identify it.
 * The script is the organization's own text and is not printed anywhere.
 *
 * @param apiName the permanent name of the custom page
 * @param label the label people read
 * @param assetId the identifier of the script; it changes when the script does, so a browser may keep the script
 *        under it
 * @param sha256 the SHA-256 of the script, which the browser may check
 * @param code the script
 */
public record CustomPageScriptView(@NotNull String apiName, @NotNull String label, @NotNull UUID assetId,
        @NotNull String sha256, @NotNull String code) {

    @Override
    public String toString() {
        return "CustomPageScriptView[redacted]";
    }
}
