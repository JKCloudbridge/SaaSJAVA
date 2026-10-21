package app.platform.metadata.internal;

import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The platform's own objects, from the definition files read at start. */
@Component
class DefinedPlatformObjects implements PlatformObjects {

    private final StandardMetadata standard;

    DefinedPlatformObjects(StandardMetadata standard) {
        this.standard = standard;
    }

    @Override
    public List<ObjectDefinition> all() {
        return standard.objects();
    }

    @Override
    public Optional<ObjectDefinition> named(String apiName) {
        return standard.object(apiName);
    }
}
