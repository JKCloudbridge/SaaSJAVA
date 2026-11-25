package app.platform.testsupport;

import app.platform.sharedkernel.ActorId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The state of the transactional outbox between test classes. Most test contexts run without the relay, so every class
 * leaves the events of its writes waiting in the shared database. A context that switches the relay on works through
 * them oldest first before it reaches an event of its own, and how long that takes depends on how many classes ran
 * before it, which is why a test that waits for a delivery must start from an empty queue.
 */
public final class TestOutbox {

    private TestOutbox() {
    }

    /**
     * Marks every waiting event as delivered, the way the relay does when it finishes one (the version moves by one, as
     * the row guard demands). The events belong to organizations of other test classes, which have finished; nothing
     * waits for them. An event that the relay of a running context holds at this moment simply loses its claim, which
     * the relay already handles.
     *
     * @return how many events were set aside
     */
    public static int discardWaiting() {
        String sql = "update outbox_event set status = 'DELIVERED', delivered_at = now(), locked_until = null, "
                + "updated_by = ?, version = version + 1 where status = 'PENDING'";
        try (Connection connection = TestDatabase.ownerConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, ActorId.SYSTEM.value());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The waiting outbox events could not be set aside", e);
        }
    }
}
