package app.platform.web.openapi;

import app.platformapi.ApiErrorResponse;
import app.platformapi.ErrorCode;
import app.platformapi.Pagination;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes the generated OpenAPI document (ADR-0011). The document must be identical on every machine and in every
 * environment, because it is committed and compared: no server URLs, no timestamps, keys in a fixed order (the
 * writer settings are in the application configuration).
 */
@Configuration
class OpenApiConfiguration {

    private static final String ERROR_SCHEMA = "ApiErrorResponse";
    private static final Pattern SCHEMA_REFERENCE = Pattern.compile("#/components/schemas/([A-Za-z0-9_.-]+)");

    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Platform API")
                        .version("v1")
                        .description("HTTP API of the multi-tenant application platform. Every successful body is "
                                + "{\"data\": ...} (collections add \"pagination\"); every failure is "
                                + "{\"error\": {code, message, fields?, requestId, traceId?}}."))
                .servers(List.of(new Server().url("/")));
    }

    /** Every operation can fail with the single error model, whatever else it documents. */
    @Bean
    OperationCustomizer standardErrorResponse() {
        return (operation, handlerMethod) -> {
            Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA);
            operation.getResponses().addApiResponse("default", new ApiResponse()
                    .description("Error. The code says what went wrong; the message is safe to show.")
                    .content(new Content().addMediaType("application/json", new MediaType().schema(reference))));
            return operation;
        };
    }

    /**
     * Publishes the types every client needs even before an endpoint returns them, and gives the error codes a
     * name of their own so clients can refer to the type instead of repeating the list.
     */
    @Bean
    OpenApiCustomizer sharedSchemas() {
        return openApi -> {
            boolean v31 = openApi.getSpecVersion() == SpecVersion.V31;
            for (Class<?> type : List.of(ApiErrorResponse.class, Pagination.class)) {
                ModelConverters.getInstance(v31).readAll(type)
                        .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            }
            StringSchema codes = new StringSchema();
            codes.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
            codes.setDescription("Stable machine-readable error code. Branch on this, never on the message.");
            openApi.getComponents().addSchemas("ErrorCode", codes);
            Schema<?> error = openApi.getComponents().getSchemas().get("ApiError");
            error.getProperties().put("code", new Schema<>().$ref("#/components/schemas/ErrorCode"));
        };
    }

    /**
     * Keeps only the schemas that some path of the document can reach (Sprint 30, ADR-0248). Other parts of the
     * platform
     * add their own schemas to every document the generator writes; the contract of the public API, which the contract
     * tests generate by pointing the generator at the public prefix, must name nothing of them. Off in the application
     * (the internal document keeps what it has), on for the run that generates the public contract
     * ({@code platform.openapi.reachable-schemas-only}). It is declared in this module because it must run after the
     * customizers that add the schemas, which the generator applies in the order the beans are registered.
     */
    @Bean
    @ConditionalOnProperty(name = "platform.openapi.reachable-schemas-only", havingValue = "true")
    OpenApiCustomizer reachableSchemasOnly() {
        // A copy of the generator's mapper: reading the model through the shared one would build and keep serializers
        // in its caches, which other documents written in the same process would then inherit.
        ObjectMapper reader = Json31.mapper().copy();
        return openApi -> {
            Map<String, Schema<?>> schemas = schemasOf(openApi);
            if (schemas == null) {
                return;
            }
            Set<String> reachable = new TreeSet<>();
            Deque<String> pending = new ArrayDeque<>();
            collect(text(reader, openApi.getPaths()), reachable, pending);
            while (!pending.isEmpty()) {
                Schema<?> schema = schemas.get(pending.pop());
                if (schema != null) {
                    collect(text(reader, schema), reachable, pending);
                }
            }
            schemas.keySet().retainAll(reachable);
        };
    }

    /** The schemas of the document; the generator's type for them is a map of raw schemas. */
    @SuppressWarnings({"rawtypes", "unchecked"}) // the model type of the schema map is raw; nothing is added to it
    private static Map<String, Schema<?>> schemasOf(OpenAPI openApi) {
        return openApi.getComponents() == null ? null : (Map) openApi.getComponents().getSchemas();
    }

    private static String text(ObjectMapper reader, Object model) {
        try {
            return reader.writeValueAsString(model);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The model of the document could not be read", e);
        }
    }

    private static void collect(String json, Set<String> reachable, Deque<String> pending) {
        Matcher reference = SCHEMA_REFERENCE.matcher(json);
        while (reference.find()) {
            if (reachable.add(reference.group(1))) {
                pending.push(reference.group(1));
            }
        }
    }
}
