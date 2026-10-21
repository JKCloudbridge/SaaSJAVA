package app.platform.metadata.internal;

import app.platform.security.ObjectCatalog;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The object catalogue the security module asks (ADR-0049, ADR-0058): which objects, and which fields of them, exist
 * for the organization. It answers from the same snapshot as {@link MetadataCatalogue}, so a permission can only be
 * given for something the metadata module really defines. Fields the platform has retired are not offered, so no new
 * permission can be given for them; permissions already given stay and keep working.
 */
@Component
class MetadataObjectCatalog implements ObjectCatalog {

    private final MetadataCatalogue catalogue;

    MetadataObjectCatalog(MetadataCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * The objects permissions can name. An object the organization lacks the plan feature for is not among them, asked
     * now and not kept (ADR-0181): a permission decision, a menu and the access-policy editor all treat it as unknown.
     */
    @Override
    public List<ObjectInfo> objects() {
        MetadataSnapshot snapshot = catalogue.snapshot();
        Set<String> offered = catalogue.objects().stream().map(object -> object.apiName()).collect(Collectors.toSet());
        if (offered.size() == snapshot.objects().size()) {
            return snapshot.catalogue();
        }
        return snapshot.catalogue().stream().filter(info -> offered.contains(info.key())).toList();
    }

    @Override
    public Optional<ObjectInfo> object(String key) {
        return catalogue.entitled(key) ? catalogue.snapshot().catalogueObject(key) : Optional.empty();
    }
}
