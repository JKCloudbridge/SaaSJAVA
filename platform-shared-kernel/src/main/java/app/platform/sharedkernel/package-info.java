/**
 * Shared kernel: small, stable types that every module may depend on.
 *
 * <p>Keep this module tiny. Anything with business meaning belongs in the module that owns it.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package app.platform.sharedkernel;

import org.springframework.modulith.ApplicationModule;
