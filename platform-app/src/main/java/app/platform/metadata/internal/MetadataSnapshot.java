package app.platform.metadata.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole object catalogue of one organization at one metadata version: the standard objects with the organization's
 * own fields added, and the organization's custom objects (ADR-0058, ADR-0061). Immutable, so it can be shared between
 * threads and kept by the cache. The answer in the shape the security module wants is prepared with it.
 */
final class MetadataSnapshot {

    private final List<ObjectDefinition> objects;
    private final Map<String, ObjectDefinition> byName = new LinkedHashMap<>();
    private final List<ObjectCatalog.ObjectInfo> catalogue;
    private final Map<String, ObjectCatalog.ObjectInfo> catalogueByName = new LinkedHashMap<>();

    MetadataSnapshot(List<ObjectDefinition> objects) {
        this.objects = List.copyOf(objects);
        this.objects.forEach(object -> byName.put(object.apiName(), object));
        this.catalogue = this.objects.stream().map(MetadataSnapshot::infoOf).toList();
        this.catalogue.forEach(info -> catalogueByName.put(info.key(), info));
    }

    List<ObjectDefinition> objects() {
        return objects;
    }

    Optional<ObjectDefinition> object(String apiName) {
        return Optional.ofNullable(byName.get(apiName));
    }

    List<ObjectCatalog.ObjectInfo> catalogue() {
        return catalogue;
    }

    Optional<ObjectCatalog.ObjectInfo> catalogueObject(String apiName) {
        return Optional.ofNullable(catalogueByName.get(apiName));
    }

    /** What permissions can name: every object and its fields that the platform still offers. */
    private static ObjectCatalog.ObjectInfo infoOf(ObjectDefinition object) {
        return new ObjectCatalog.ObjectInfo(object.apiName(), object.label(),
                object.fields().stream().filter(field -> !field.retired())
                        .map(field -> new ObjectCatalog.FieldInfo(field.apiName(), field.label())).toList());
    }
}
