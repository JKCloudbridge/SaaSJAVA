package app.platform.data;

/**
 * One key of the order of a query. Records without a value in the field come last in both directions; ties are broken
 * by the identifier (in the direction of the last key), so a page boundary is always exact (ADR-0100).
 *
 * @param field the API name of the field
 * @param descending whether the largest comes first
 */
public record QuerySort(String field, boolean descending) {
}
