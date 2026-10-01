package app.platform.web.openapi;

import app.platformapi.ApiErrorResponse;
import app.platformapi.ErrorCode;
import app.platformapi.Pagination;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
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
}
