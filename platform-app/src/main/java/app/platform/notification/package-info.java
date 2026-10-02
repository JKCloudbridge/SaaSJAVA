/**
 * Notification module: e-mail now (Sprint 4), in-app notifications later.
 *
 * <p>The module has no public types of its own: other modules ask for a mail through the
 * {@link app.platform.sharedkernel.mail.MailQueue} contract of the shared kernel (the same pattern as the audit
 * contract), and this module supplies the queue, the relay that sends with retries, the SMTP transport and the texts
 * (ADR-0024). It is the only module that may create the one-time link tokens of the identity module, at send time.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is
 * internal. Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "identity",
            "observability"
        })
package app.platform.notification;

import org.springframework.modulith.ApplicationModule;
