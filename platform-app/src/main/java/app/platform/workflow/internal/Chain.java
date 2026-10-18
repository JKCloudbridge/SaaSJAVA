package app.platform.workflow.internal;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The chain of workflow executions that led to an event (ADR-0140): who started it, how many executions deep it is and
 * which workflows are in it already. Identifiers and a number only. The pure rules (is this workflow in the chain, is
 * the chain too deep) live here so they are tested without a database.
 *
 * @param root the first cause (the event of a person's write, or the execution a schedule firing started), or null
 *        when the event carries none (a person's write)
 * @param depth how many workflow executions lead to the event; a person's write has 0
 * @param workflows the workflows already in the chain, not counting any that would start now
 */
record Chain(UUID root, int depth, List<UUID> workflows) {

    /** Copies the list. */
    Chain {
        workflows = List.copyOf(workflows);
    }

    /** The chain of an event that no workflow caused. */
    static Chain none() {
        return new Chain(null, 0, List.of());
    }

    /**
     * The chain an event's payload carries; a payload with none, or one that cannot be read, is the empty chain (an old
     * event is depth 0). Unreadable is treated as empty and never as an error: refusing to start workflows because of a
     * malformed optional field would lose the change.
     */
    static Chain fromPayload(JsonNode payload) {
        JsonNode node = payload.path("chain");
        if (!node.isObject()) {
            return none();
        }
        try {
            UUID root = UUID.fromString(node.path("root").asString(""));
            int depth = node.path("depth").asInt(0);
            List<UUID> workflows = new java.util.ArrayList<>();
            node.path("workflows").forEach(id -> workflows.add(UUID.fromString(id.asString(""))));
            return new Chain(root, Math.max(0, depth), workflows);
        } catch (IllegalArgumentException unreadable) {
            return none();
        }
    }

    /** Whether the workflow is in the chain already: starting it again would be a loop. */
    boolean contains(UUID workflow) {
        return workflows.contains(workflow);
    }

    /** Whether one more execution would go beyond the deepest chain allowed. */
    boolean isTooDeep(int maxDepth) {
        return depth >= maxDepth;
    }

    /** The chain an action's write carries: this execution is one deeper and its workflow is in it. */
    Chain extendedBy(UUID workflow, UUID executionRoot) {
        List<UUID> more = new java.util.ArrayList<>(workflows);
        more.add(workflow);
        return new Chain(root == null ? executionRoot : root, depth + 1, more);
    }
}
