/**
 * Notification module: e-mail (Sprint 4) and in-app notices with templates and preferences (Sprint 25).
 *
 * <p>The module has no public types of its own: other modules ask for a mail through the
 * {@link app.platform.sharedkernel.mail.MailQueue} contract of the shared kernel and for an in-app notice through
 * {@link app.platform.sharedkernel.notice.Notices} (the same pattern as the audit contract), and this module supplies
 * the queue, the relay that sends with retries, the SMTP transport, the texts (ADR-0024) and the notice tables and
 * their endpoints (ADR-0154 to ADR-0158). It is the only module that may create the one-time link tokens of the
 * identity module, at send time, and the only one that names the notice tables.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests. Since Sprint 25
 * the module reads the text an administrator wrote and the labels of objects, processes and workflows through
 * {@code metadata}; {@code metadata} never depends on it.
 */
@ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity",
            "metadata",
            "observability"
        })
package app.platform.notification;

import org.springframework.modulith.ApplicationModule;
