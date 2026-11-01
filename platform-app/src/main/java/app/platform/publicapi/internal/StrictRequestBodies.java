package app.platform.publicapi.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the bodies of the public API strictly (Sprint 30, ADR-0245): a property that the contract does not have is an
 * error with its place, never silently dropped. The application's own mapper ignores unknown properties, because the
 * internal interface has always been lenient and its clients rely on it, and an annotation on a type cannot make a
 * lenient mapper strict. So the public types get a reader of their own: the application's mapper, with the one switch
 * turned the other way. It reads only the types of the public module; every answer is still written by the
 * application's mapper, so nothing about how a value is written differs between the two interfaces.
 */
@Configuration
class StrictRequestBodies implements WebMvcConfigurer {

    private static final String PUBLIC_TYPES = "app.platform.publicapi.";

    private final JsonMapper application;

    StrictRequestBodies(JsonMapper application) {
        this.application = application;
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        // A custom converter is asked before the defaults, so the public types meet this reader first.
        builder.addCustomConverter(new StrictReader(application.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()));
    }

    /** A converter that reads the types of the public module and writes nothing. */
    private static final class StrictReader extends JacksonJsonHttpMessageConverter {

        StrictReader(JsonMapper mapper) {
            super(mapper);
        }

        @Override
        public boolean canRead(ResolvableType type, MediaType mediaType) {
            Class<?> raw = type.resolve();
            return raw != null && raw.getName().startsWith(PUBLIC_TYPES) && super.canRead(type, mediaType);
        }

        @Override
        public boolean canWrite(ResolvableType targetType, Class<?> valueClass, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }
    }
}
