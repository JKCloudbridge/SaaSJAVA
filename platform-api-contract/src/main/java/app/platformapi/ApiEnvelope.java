package app.platformapi;

/**
 * Marker of every JSON body the API returns: a single object ({@link ApiResponse}), a page
 * ({@link ApiPageResponse}) or an error ({@link ApiErrorResponse}). Controllers return these types so that
 * clients always find results under {@code data}, paging under {@code pagination} and failures under
 * {@code error}; an architecture test enforces it.
 */
public sealed interface ApiEnvelope permits ApiResponse, ApiPageResponse, ApiErrorResponse {
}
