package app.platform.sheets.internal;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the module says about its two formats in one place (SH1A, ADR-0251): the fixed words of the refusals that
 * belong to the switch and to the fill-in, and the way to tell which format a stored grid is in. The words never
 * repeat anything of the sheet.
 */
final class SheetFormats {

    /** Said when something would store a document of format version 2 while the switch is off. */
    static final String EARLIER_FORMAT = "This sheet is still in the earlier format.";

    private SheetFormats() {
    }

    /** Whether the stored grid text is a document of format version 2. */
    static boolean isFormatTwo(JsonNode stored) {
        return SheetGridValidator.isFormatTwo(stored);
    }

    /**
     * Whether copying a version as a new draft must be refused: the switch is off and the version to copy is in format
     * version 2. A copy stores a document, and with the switch off nothing may store one of that format.
     */
    static boolean copyRefused(SheetProperties properties, SheetVersionStore store, JsonMapper json, String object,
            UUID owner, int base) {
        return !properties.formatTwo().enabled() && store.findGrid(object, owner, base)
                .map(found -> isFormatTwo(json.readTree(found.grid()))).orElse(false);
    }
}
