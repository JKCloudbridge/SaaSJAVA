package app.platform.security.internal;

import app.platform.security.ObjectCatalog;
import java.util.List;

/**
 * A fixed object catalogue for the unit tests of the decision rules: the same objects every time, with no database.
 * The application's own catalogue comes from the metadata module (ADR-0058).
 */
final class FixedObjectCatalog implements ObjectCatalog {

    private final List<ObjectInfo> objects;

    FixedObjectCatalog(List<ObjectInfo> objects) {
        this.objects = List.copyOf(objects);
    }

    @Override
    public List<ObjectInfo> objects() {
        return objects;
    }
}
