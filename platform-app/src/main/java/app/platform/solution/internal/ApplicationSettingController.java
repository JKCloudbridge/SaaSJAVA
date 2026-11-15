package app.platform.solution.internal;

import app.platform.solution.internal.ApplicationSettingViews.ApplicationSettingsView;
import app.platform.solution.internal.ApplicationSettingViews.SaveApplicationSettingRequest;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an organization may choose for each application it has (MC2B, ADR-0261). Thin: the rules are in
 * {@link ApplicationSettingService}. Answered on an organization host only, for a member who holds
 * {@code application-settings.manage}; the application and the setting travel in bodies and parameters that are keys,
 * never a typed text.
 */
@RestController
@Tag(name = "Application settings")
class ApplicationSettingController {

    private static final String BASE = ApiPaths.V1 + "/application-settings";

    private final ApplicationSettingService settings;

    ApplicationSettingController(ApplicationSettingService settings) {
        this.settings = settings;
    }

    @GetMapping(BASE)
    @Operation(operationId = "listApplicationSettings", summary = "The settings each application offers",
            description = "Needs application-settings.manage. Every setting of every application the organization has "
                    + "that offers one: its words, the value in force, the default, the range and the version to "
                    + "send when changing it. The settings are declared by the application's solution file; nothing "
                    + "is listed for an application the organization does not have.")
    ApiResponse<ApplicationSettingsView> list() {
        return ApiResponse.of(settings.view());
    }

    @PutMapping(BASE)
    @Operation(operationId = "saveApplicationSetting", summary = "Choose a value for a setting",
            description = "Needs application-settings.manage. Without a version it makes the organization's first "
                    + "choice for the setting (CONFLICT when it already has one); with the version read it changes "
                    + "that choice. The value must be one the setting's kind takes. A change applies from the next "
                    + "question: a review that ends afterwards uses it. Audited by the application and the setting, "
                    + "never the value.")
    ApiResponse<ApplicationSettingsView> save(@Valid @RequestBody SaveApplicationSettingRequest body) {
        return ApiResponse.of(settings.save(body));
    }

    @DeleteMapping(BASE)
    @Operation(operationId = "resetApplicationSetting", summary = "Take a choice back",
            description = "Needs application-settings.manage and the version read. The application's own default "
                    + "applies again.")
    ApiResponse<ApplicationSettingsView> reset(@RequestParam String application, @RequestParam String key,
            @RequestParam long version) {
        return ApiResponse.of(settings.reset(application, key, version));
    }
}
