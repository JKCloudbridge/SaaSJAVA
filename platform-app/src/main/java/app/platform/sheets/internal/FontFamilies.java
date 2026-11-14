package app.platform.sheets.internal;

import java.util.Map;

/**
 * The font family a file is written with for each of the sixteen keys of the closed list (SH3, ADR-0251, ADR-0253). It
 * is the other half of {@link FontKeys}, which maps a family name read from a file to a key: a key is written as
 * one fixed name, never as any other, and a test holds the two tables together (the name written for a key is read
 * back as the same key). A computer that lacks a family shows a nearby one; the key, not the name, is what the sheet
 * stores.
 *
 * <p>The three general keys use families that stand in for the common ones with the same measurements and that no
 * row of the reading table claims, so they stay distinct from the named keys after a round trip.
 */
final class FontFamilies {

    private static final Map<String, String> FAMILY = Map.ofEntries(Map.entry("SANS_NEUTRAL", "Arial"),
            Map.entry("SANS_HUMANIST", "Calibri"), Map.entry("SANS_WIDE", "Verdana"),
            Map.entry("SANS_FRIENDLY", "Trebuchet MS"), Map.entry("SANS_UNICODE", "Arial Unicode MS"),
            Map.entry("DISPLAY_HEAVY", "Arial Black"), Map.entry("DISPLAY_IMPACT", "Impact"),
            Map.entry("SERIF_TEXT", "Times New Roman"), Map.entry("SERIF_SCREEN", "Georgia"),
            Map.entry("SERIF_SLAB", "Cambria"), Map.entry("MONO_CLASSIC", "Courier New"),
            Map.entry("MONO_CODE", "Consolas"), Map.entry("SANS_UI", "Segoe UI"),
            Map.entry("SANS", "Liberation Sans"), Map.entry("SERIF", "Liberation Serif"),
            Map.entry("MONO", "Liberation Mono"));

    /** The family a file that has no font key uses: what a new workbook already has. */
    static final String PAGE_DEFAULT = "Calibri";

    private FontFamilies() {
    }

    /**
     * The family written for a key.
     *
     * @param key a key of {@link SheetLook#FONTS}, or null
     * @return the family; the page's own for null or an unknown key
     */
    static String of(String key) {
        String found = key == null ? null : FAMILY.get(key);
        return found == null ? PAGE_DEFAULT : found;
    }
}
