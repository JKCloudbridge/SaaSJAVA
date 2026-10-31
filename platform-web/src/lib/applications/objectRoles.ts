import type { MyApplication } from "./entries";

/**
 * Which object plays which part in the member's applications (FA13B, ADR-0226). The API lists it with each application
 * (`objectRoles`); the screens of an application of one kind read their object names here and hold none. The names of the
 * parts are the words of the screens that use them: this module only carries them.
 */
let known: readonly MyApplication[] = [];

/** Remembers the applications the API listed (the provider calls this whenever it has a list). */
export function rememberApplications(applications: readonly MyApplication[]): void {
  known = applications;
}

/** The objects that play the parts of an application, or an empty mapping for an application that declares none. */
export function objectRolesOf(application: MyApplication | undefined): Readonly<Record<string, string>> {
  return application?.objectRoles ?? {};
}

/** The parts of the remembered application with the API name, or an empty mapping. */
export function rolesOfApplication(apiName: string): Readonly<Record<string, string>> {
  return known.find((application) => application.apiName === apiName)?.objectRoles ?? {};
}

/** The part an object plays in some application of the member, or undefined. */
export function roleOfObject(object: string): string | undefined {
  for (const application of known) {
    for (const [role, name] of Object.entries(application.objectRoles ?? {})) {
      if (name === object) {
        return role;
      }
    }
  }
  return undefined;
}

/** The first application of the member that has the object as one of its parts, or undefined. */
export function applicationOfObject(object: string): string | undefined {
  return known.find((application) => Object.values(application.objectRoles ?? {}).includes(object))?.apiName;
}
