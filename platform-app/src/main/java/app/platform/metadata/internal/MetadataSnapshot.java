package app.platform.metadata.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.security.ObjectCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole published metadata of one organization at one metadata version: the standard objects with the
 * organization's own fields added, its custom objects and its record types (ADR-0058, ADR-0061, ADR-0064). Immutable,
 * so it can be shared between threads and kept by the cache. The answer in the shape the security module wants is
 * prepared with it.
 */
final class MetadataSnapshot {

    private final List<ObjectDefinition> objects;
    private final Map<String, ObjectDefinition> byName = new LinkedHashMap<>();
    private final Map<String, List<RecordType>> recordTypes;
    private final List<ObjectCatalog.ObjectInfo> catalogue;
    private final Map<String, ObjectCatalog.ObjectInfo> catalogueByName = new LinkedHashMap<>();

    MetadataSnapshot(List<ObjectDefinition> objects, Map<String, List<RecordType>> recordTypes) {
        this.objects = List.copyOf(objects);
        this.objects.forEach(object -> byName.put(object.apiName(), object));
        Map<String, List<RecordType>> kept = new LinkedHashMap<>();
        recordTypes.forEach((object, list) -> kept.put(object, List.copyOf(list)));
        this.recordTypes = Collections.unmodifiableMap(kept);
        this.catalogue = this.objects.stream().map(MetadataSnapshot::infoOf).toList();
        this.catalogue.forEach(info -> catalogueByName.put(info.key(), info));
    }

    List<ObjectDefinition> objects() {
        return objects;
    }

    Optional<ObjectDefinition> object(String apiName) {
        return Optional.ofNullable(byName.get(apiName));
    }

    /** The record types of an object, in the order they were made; empty when it has none. */
    List<RecordType> recordTypes(String objectApiName) {
        return recordTypes.getOrDefault(objectApiName, List.of());
    }

    /** Every record type of the organization. */
    List<RecordType> allRecordTypes() {
        return recordTypes.values().stream().flatMap(List::stream).toList();
    }

    Optional<RecordType> recordType(String objectApiName, String apiName) {
        return recordTypes(objectApiName).stream().filter(type -> type.apiName().equals(apiName)).findFirst();
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
