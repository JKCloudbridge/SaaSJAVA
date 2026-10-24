package app.platform.sheets.internal;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * Wiring of the sheets module: its settings, the schema and the validator, and the publication of the grid schema in
 * the OpenAPI document (FA3, ADR-0186). The schemas in {@code components.schemas} are the file the validator checks
 * against, so the contract the browser codes against, the contract the document states and the contract the server
 * enforces are one text.
 */
@Configuration
@EnableConfigurationProperties({SheetProperties.class, FillProperties.class, FillArchiveProperties.class,
        ImportProperties.class})
class SheetModuleConfiguration {

    @Bean
    SheetSchemas sheetSchemas() {
        return new SheetSchemas();
    }

    @Bean
    SheetGridValidator sheetGridValidator(SheetProperties properties, SheetSchemas schemas) {
        return new SheetGridValidator(properties.limits(), schemas);
    }

    @Bean
    FillValidator fillValidator(FillProperties fills, SheetProperties properties) {
        return new FillValidator(fills, properties.limits());
    }

    @Bean
    OpenApiCustomizer sheetGridSchemas(SheetSchemas schemas) {
        return openApi -> {
            for (java.util.Map.Entry<String, JsonNode> entry : schemas.publishedSchemas().properties()) {
                openApi.getComponents().addSchemas(entry.getKey(), parse(entry.getValue()));
            }
            for (String name : java.util.List.of("SheetVersionView", "SheetFillView")) {
                Schema<?> holder = openApi.getComponents().getSchemas().get(name);
                if (holder != null && holder.getProperties() != null) {
                    holder.getProperties().put("grid", new Schema<>().$ref("#/components/schemas/SheetGrid"));
                }
            }
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
