package app.platformapi;

import jakarta.validation.constraints.NotNull;

/** A safe local destination, or the notices list with a fixed explanation. No record values. */
public record NotificationOpenView(@NotNull String href, String explanation) { }
