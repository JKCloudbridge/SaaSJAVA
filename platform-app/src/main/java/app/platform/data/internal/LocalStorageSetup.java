package app.platform.data.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Makes the bucket of the local object store when the application starts on a developer machine (profile {@code
 * local}). A deployment provisions its bucket with the rest of its infrastructure and has no such step. A store that is
 * not running does not stop the application: files answer 503 until it is.
 */
@Component
@Profile("local")
class LocalStorageSetup implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageSetup.class);

    private final ObjectStore objects;

    LocalStorageSetup(ObjectStore objects) {
        this.objects = objects;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!objects.configured()) {
            return;
        }
        try {
            objects.ensureBucket();
        } catch (ObjectStoreException e) {
            LOG.warn("The local object store did not answer; files will answer 503 until it does");
        }
    }
}
