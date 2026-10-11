package app.platform.metadata.internal;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * Publishes the JSON schemas of layouts, record pages and applications in {@code components.schemas} of the OpenAPI
 * document and points the {@code definition} of every request and view at them (ADR-0070). The application checks
 * every saved definition against these very schemas, and the generated TypeScript client gets its types from them, so
 * the contract the browser codes against, the contract the document states and the contract the server enforces are
 * one text ({@code metadata-definitions.json} of the contract module).
 */
@Configuration
class DefinitionOpenApi {

    /** Which schema describes the {@code definition} of which request or view. */
    private static final Map<String, String> DEFINITIONS = Map.ofEntries(
            Map.entry("CreateLayoutRequest", "PageLayoutDefinition"),
            Map.entry("UpdateLayoutRequest", "PageLayoutDefinition"),
            Map.entry("LayoutView", "PageLayoutDefinition"),
            Map.entry("CreateRecordPageRequest", "RecordPageDefinition"),
            Map.entry("UpdateRecordPageRequest", "RecordPageDefinition"),
            Map.entry("RecordPageView", "RecordPageDefinition"),
            Map.entry("CreateApplicationRequest", "ApplicationDefinition"),
            Map.entry("UpdateApplicationRequest", "ApplicationDefinition"),
            Map.entry("ApplicationView", "ApplicationDefinition"));

    @Bean
    OpenApiCustomizer metadataDefinitionSchemas(DefinitionSchemas schemas) {
        return openApi -> {
            for (Map.Entry<String, JsonNode> entry : schemas.publishedSchemas().properties()) {
                openApi.getComponents().addSchemas(entry.getKey(), parse(entry.getValue()));
            }
            DEFINITIONS.forEach((owner, schema) -> {
                Schema<?> holder = openApi.getComponents().getSchemas().get(owner);
                if (holder != null && holder.getProperties() != null) {
                    holder.getProperties().put("definition", new Schema<>().$ref("#/components/schemas/" + schema));
                }
            });
        };
    }

    private static Schema<?> parse(JsonNode node) {
        try {
            return Json31.mapper().readValue(node.toString(), Schema.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("A published schema cannot be read", e);
        }
    }
}
