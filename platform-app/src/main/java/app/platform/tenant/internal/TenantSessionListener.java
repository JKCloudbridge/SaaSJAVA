package app.platform.tenant.internal;

import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Copies the tenant context into the database session at the start of every transaction (ADR-0003, ADR-0015): the
 * transaction-local setting {@code app.current_tenant}, or for platform work {@code app.system_scope}. Row level
 * security compares every row's tenant with that setting.
 *
 * <p>The setting is transaction-local ({@code set_config(..., true)}), so when the transaction ends the connection
 * goes back to the pool without it; a later transaction on the same connection starts clean and sets its own. A
 * transaction that begins with no context sets nothing and therefore sees no tenant rows at all.
 *
 * <p>Unlike the correlation stamp, this is not best effort: if the setting cannot be made, the transaction is marked
 * rollback-only (and the failed statement has already aborted it), so no work can run with a missing or wrong tenant.
 */
@Component
class TenantSessionListener implements TransactionExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(TenantSessionListener.class);

    private final DataSource dataSource;
    private final TenantContexts contexts;

    TenantSessionListener(DataSource dataSource, TenantContexts contexts) {
        this.dataSource = dataSource;
        this.contexts = contexts;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        Optional<String> tenant = contexts.current().map(TenantContext::tenantId).map(Object::toString);
        Optional<String> scope = contexts.currentSystemScope().map(SystemScope::settingValue);
        if (tenant.isEmpty() && scope.isEmpty()) {
            return;
        }
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder)) {
            LOG.error("A tenant context is active but the transaction has no database connection to carry it");
            transaction.setRollbackOnly();
            return;
        }
        String setting = tenant.isPresent() ? "app.current_tenant" : "app.system_scope";
        String value = tenant.orElseGet(scope::orElseThrow);
        try (PreparedStatement statement = holder.getConnection().prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, setting);
            statement.setString(2, value);
            statement.execute();
        } catch (SQLException e) {
            // The driver's message is not logged: it can quote what was being set.
            LOG.error("Could not put the tenant context into the database session, SQL state {}", e.getSQLState());
            transaction.setRollbackOnly();
        }
    }
}
