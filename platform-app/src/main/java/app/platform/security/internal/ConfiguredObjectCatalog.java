package app.platform.security.internal;

import app.platform.security.ObjectCatalog;
import java.util.List;

/**
 * The stand-in object catalogue (ADR-0049): the same objects for every organization, from the settings, until the
 * metadata module (Sprint 10) provides objects that organizations define themselves.
 */
final class ConfiguredObjectCatalog implements ObjectCatalog {

    private final List<ObjectInfo> objects;

    ConfiguredObjectCatalog(List<ObjectInfo> objects) {
        this.objects = List.copyOf(objects);
    }

    @Override
    public List<ObjectInfo> objects() {
        return objects;
    }
}
