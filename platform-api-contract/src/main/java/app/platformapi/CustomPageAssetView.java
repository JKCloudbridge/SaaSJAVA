package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * An uploaded script of a custom page (Sprint 18, ADR-0127), identified by its content. Name it in the content of a
 * custom page ({@code frontend}); the same script uploaded twice gives the same asset.
 *
 * @param id the identifier to name in the custom page
 * @param sha256 the SHA-256 of the bytes, in lower case hexadecimal
 * @param size the number of bytes
 * @param created whether this upload made the asset (false: the same script was uploaded before)
 */
public record CustomPageAssetView(@NotNull UUID id, @NotNull String sha256, @NotNull Long size,
        @NotNull Boolean created) {
}
