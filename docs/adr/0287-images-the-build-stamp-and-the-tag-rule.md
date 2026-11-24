# ADR-0287: Images, the build stamp, and the tag rule (S33)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Sprint:** S33
- **Related:** [ADR-0013](0013-local-deployment-approach.md), [ADR-0286](0286-deployment-topology-the-ingress-and-the-forwarded-headers.md), [ADR-0288](0288-migrations-as-a-job-the-compatibility-rule-and-rollback.md), [ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)

## Decision

1. **Three images, each built once and promoted.** `platform-app` (the application; its second command `migrate` is the migration
   job), `platform-web` (the web server, a production build, already existing, reviewed and extended here) and `platform-edge`
   (the sidecar of [ADR-0286](0286-deployment-topology-the-ingress-and-the-forwarded-headers.md)). The same image runs in every
   environment; only settings and secrets differ. Nothing is rebuilt per environment.
2. **Non-root, small, with a health check.** Each runs as a user without rights, on an Alpine base with operating-system fixes
   applied at build, UTC, no development dependency (the web server runs the traced standalone output; the application the layered
   jar). Each image answers its own health check; the application's entry point (`entrypoint.sh`) takes the commands `serve`
   (default) and `migrate` and refuses any other with exit code 64.
3. **The JVM stops on an out-of-memory error** (`-XX:+ExitOnOutOfMemoryError`) so the container service restarts it rather than
   leaving a half-alive process; memory is a share of the container (`MaxRAMPercentage=75`).
4. **A release stamp that cannot carry a secret.** The build gives `GIT_COMMIT` (hexadecimal) and `BUILD_TIME` (an instant). They
   become `PLATFORM_BUILD_COMMIT` and `PLATFORM_BUILD_TIME`, appear in `/actuator/info` (management port, never routed) and in one
   log line at start, and as image labels. Only a value of the right shape is shown; anything else shows as `unknown`, so no text of
   anyone's choosing reaches a log line through the stamp. The version comes from `build-info.properties` of the build.
5. **The tag rule.** An image is tagged with the commit that built it (CI: the full commit identifier). The registry's repositories
   refuse to move a tag (immutable), scan on push and keep the last sixty images. **`latest` is never deployed**: the infrastructure
   variables refuse an image reference that ends in it. A release names its three tags; a rollback names the previous three.
6. **The scan gate.** CI builds and scans all three images for fixable HIGH and CRITICAL findings, as it already did for the
   application, and starts the web and edge images (non-root, health check, a stop that drains, no secret in the environment; the
   edge configuration validated by the proxy itself). CI never pushes an image and holds no cloud credentials.
7. **Key files come from the secret store through the entry point.** Variables named `PLATFORM_KEYFILE__<name>` (or the Base64
   form `PLATFORM_KEYFILE_B64__<name>`) are written to private files and removed from the environment before the application
   starts, so the signing keys, which the application reads from files, can be given by a secret store that hands out values.

## Consequences

- The web server also gets an entry point: on a stop it turns `/healthz` to 503, keeps serving for `WEB_SHUTDOWN_DELAY` seconds
  and then stops, because the server itself exits at once ([ADR-0289](0289-zero-downtime-releases-the-drain-and-the-threshold-of-the-proof.md)).
- The base image tags are pinned to a version; moving them is a reviewed change, like a dependency.
- The registry is the one resource shared between environments (an artifact store the environments only read): [ADR-0291](0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md).
