package app.platform.metadata.internal;

import app.platform.security.ObjectCatalog;
import java.util.List;
import java.util.Optional;
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

    @Override
    public List<ObjectInfo> objects() {
        return catalogue.snapshot().catalogue();
    }

    @Override
    public Optional<ObjectInfo> object(String key) {
        return catalogue.snapshot().catalogueObject(key);
    }
}
