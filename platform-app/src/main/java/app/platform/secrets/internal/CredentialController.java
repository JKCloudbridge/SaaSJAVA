package app.platform.secrets.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CredentialView;
import app.platformapi.WriteCredentialRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credentials of an organization (Sprint 27, ADR-0213). Organization hosts only: the caller must be a member, hold
 * the ability and have the plan feature. The value of a credential travels in a body, once, on its way in; there is no
 * endpoint that returns it, shows it or copies it, and none can be added without an architecture test failing.
 */
@RestController
@Tag(name = "Credentials")
class CredentialController {

    private static final String ROOT = ApiPaths.CREDENTIALS;
    private final CredentialService credentials;

    CredentialController(CredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping(ROOT)
    @Operation(operationId = "listCredentials", summary = "The organization's credentials",
            description = "Needs credentials.view. Names, versions, dates and how many connections use each. "
                    + "Never a value, and never whether a value is valid.")
    ApiResponse<List<CredentialView>> list() {
        return ApiResponse.of(credentials.list());
    }

    @PutMapping(ROOT + "/{name}")
    @Operation(operationId = "writeCredential", summary = "Add a credential or replace its value",
            description = "Needs credentials.view and credentials.manage. The value is in the body and is sealed "
                    + "at once; it is never returned, shown, logged or audited. Writing to a name that exists makes a "
                    + "new version and marks the last test of every connection that uses it as void.")
    ApiResponse<CredentialView> write(@PathVariable String name, @Valid @RequestBody WriteCredentialRequest body) {
        return ApiResponse.of(credentials.write(name, body));
    }

    @PostMapping(ROOT + "/{name}/rollback")
    @Operation(operationId = "rollBackCredential", summary = "Go back to the version before",
            description = "Needs credentials.view and credentials.manage. The version before the current one "
                    + "becomes the current value again, as a new version. No one is shown a value.")
    ApiResponse<CredentialView> rollback(@PathVariable String name) {
        return ApiResponse.of(credentials.rollback(name));
    }

    @DeleteMapping(ROOT + "/{name}")
    @Operation(operationId = "removeCredential", summary = "Remove a credential and all its versions",
            description = "Needs credentials.view and credentials.manage. Refused while a connection that is "
                    + "switched on uses it.")
    ApiResponse<Boolean> remove(@PathVariable String name) {
        credentials.remove(name);
        return ApiResponse.of(Boolean.TRUE);
    }
}
