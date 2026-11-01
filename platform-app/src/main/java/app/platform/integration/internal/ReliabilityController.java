package app.platform.integration.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.AttemptView;
import app.platformapi.CircuitRequest;
import app.platformapi.CircuitView;
import app.platformapi.DeliveryView;
import app.platformapi.FlowLimitsRequest;
import app.platformapi.FlowLimitsView;
import app.platformapi.IntegrationHealthView;
import app.platformapi.ReplayRequest;
import app.platformapi.ReplayView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitoring and operating the flows of a connection (Sprint 29): the attempts of a delivery, sending a dead letter
 * again, the circuit breaker, the limits on sending, and health. Organization hosts only; every endpoint is decided by
 * the caller's abilities and the plan feature in {@link ReliabilityService}. Nothing returned holds a record value, a
 * payload, a header, an address or an identifier of the other system.
 */
@RestController
@Tag(name = "Integrations")
class ReliabilityController {

    private final ReliabilityService reliability;

    ReliabilityController(ReliabilityService reliability) {
        this.reliability = reliability;
    }

    @GetMapping(ApiPaths.DELIVERY_ATTEMPTS)
    @Operation(operationId = "listDeliveryAttempts", summary = "The attempts made to send one delivery",
            description = "Needs integrations.view and mappings.view. When each attempt began, how long it took, how "
                    + "it ended, a stable reason and a class of status. Never a payload, a header, an address or an "
                    + "identifier of the other system.")
    ApiResponse<List<AttemptView>> attempts(@PathVariable String name, @PathVariable UUID delivery) {
        return ApiResponse.of(reliability.attempts(name, delivery));
    }

    @PostMapping(ApiPaths.DELIVERY_REPLAY)
    @Operation(operationId = "replayDelivery", summary = "Send one dead letter again",
            description = "Needs integrations.view and integrations.replay. Only a delivery that was set aside: it "
                    + "becomes a new attempt series that reads the record as it is now and never repeats what the "
                    + "link says was done. Two clicks make one.")
    ApiResponse<DeliveryView> replay(@PathVariable String name, @PathVariable UUID delivery) {
        return ApiResponse.of(reliability.replay(name, delivery));
    }

    @PostMapping(ApiPaths.DELIVERIES_REPLAY)
    @Operation(operationId = "replayDeliveries", summary = "Send the oldest dead letters again",
            description = "Needs integrations.view and integrations.replay. At most the limit (1 to 500, 100 when "
                    + "absent), the oldest first.")
    ApiResponse<ReplayView> replayAll(@PathVariable String name, @Valid @RequestBody ReplayRequest body) {
        return ApiResponse.of(reliability.replayAll(name, body.limit()));
    }

    @GetMapping(ApiPaths.CIRCUIT)
    @Operation(operationId = "getCircuit", summary = "The circuit breaker of a connection",
            description = "Needs integrations.view. Where the circuit stands and what the caller may do about it.")
    ApiResponse<CircuitView> circuit(@PathVariable String name) {
        return ApiResponse.of(reliability.circuit(name));
    }

    @PostMapping(ApiPaths.CIRCUIT)
    @Operation(operationId = "actOnCircuit", summary = "Force the circuit open or closed, or back to automatic",
            description = "Needs integrations.view and integrations.operate. The action is one the circuit's own "
                    + "answer offers. Forcing it open sends nothing and loses nothing: changes wait in the queue.")
    ApiResponse<CircuitView> act(@PathVariable String name, @Valid @RequestBody CircuitRequest body) {
        return ApiResponse.of(reliability.act(name, body.action()));
    }

    @PutMapping(ApiPaths.FLOW_LIMITS)
    @Operation(operationId = "setFlowLimits", summary = "Set how fast a connection sends",
            description = "Needs integrations.view and integrations.operate. A number above the platform's ceiling "
                    + "is refused; an absent one leaves the platform's default in force.")
    ApiResponse<FlowLimitsView> limits(@PathVariable String name, @Valid @RequestBody FlowLimitsRequest body) {
        return ApiResponse.of(reliability.setLimits(name, body));
    }

    @GetMapping(ApiPaths.CONNECTION_HEALTH)
    @Operation(operationId = "getIntegrationHealth", summary = "How the flows of one connection stand",
            description = "Needs integrations.view. The circuit, the queue by state, the limits and the last signs "
                    + "of life: counts and times only.")
    ApiResponse<IntegrationHealthView> health(@PathVariable String name) {
        return ApiResponse.of(reliability.health(name));
    }

    @GetMapping(ApiPaths.INTEGRATION_HEALTH)
    @Operation(operationId = "listIntegrationHealth", summary = "How the flows of every connection stand",
            description = "Needs integrations.view. One entry per connection of the organization.")
    ApiResponse<List<IntegrationHealthView>> healthAll() {
        return ApiResponse.of(reliability.healthAll());
    }
}
