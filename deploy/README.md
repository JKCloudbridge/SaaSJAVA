# deploy: everything that puts the platform on a real environment

Sprint 33. Decisions: [ADR-0286](../docs/adr/0286-deployment-topology-the-ingress-and-the-forwarded-headers.md) to
[ADR-0295](../docs/adr/0295-what-sprint-33-leaves-out.md). **Nothing here has been applied to any cloud account.** The files are
formatted and validated by CI, the images and the procedures are proved on the rehearsal (`infra/rehearsal`), and the ordered list of
what only the owner can do is `Sprint 33 manual steps.md`.

| Folder | What |
|--------|------|
| `edge/` | The edge sidecar image: a reverse proxy with the platform's rules (`api-rules.caddy`: body sizes that also hold for a chunked body, time limits, one honest `X-Forwarded-For`). The rehearsal's ingress imports the same rules file |
| `infra/bootstrap/` | Run **once** by the owner, with a local state: the two state buckets and the image registry |
| `infra/stack/` | The one root module every environment uses |
| `infra/modules/` | One folder per layer: `network`, `data`, `secrets`, `edge`, `app`, `observability` |
| `infra/values/` | One `<environment>.tfvars` and one `<environment>.backend.hcl` per environment (`staging`, `production`) |
| `alerts/alerts.json` | The one list of alarms; `docs/runbooks/alerts.md` explains each |

## The first command

From `platform/deploy/infra/stack`, with OpenTofu or Terraform installed and the cloud credentials of the owner in the environment:

```
tofu init  -backend-config=values/staging.backend.hcl
tofu plan  -var-file=values/staging.tfvars -var alarm_email=<your address> -var api_image_tag=<tag> -var web_image_tag=<tag> -var edge_image_tag=<tag>
```

**Read the plan.** Apply only after you have, and only by hand. Replace every `CHANGE-ME` in the values file first (region, domain,
registry, instance class after the storage benchmark, budget). Details, the bootstrap, the secrets and every manual step:
`Sprint 33 manual steps.md`. Day-to-day: `docs/runbooks/deploy.md`.

## Rules the files obey (held by tests that run in CI)

- No secret value in any file (`SecretShapedTextTest`); names and permissions only.
- The numbers of a release are in the right order (`DrainNumbersTest`).
- The two environments set the same variables, share no resource name and differ where safety needs it (`EnvironmentIsolationTest`).
- Every setting the application cannot start without is in `docs/runbooks/environments.md` and in these files, and no file names a
  variable the application does not read (`ConfigurationDocumentedTest`).
- Every alert has a section in the runbook and the other way round (`AlertsFileTest`).
- CI holds no cloud credentials and never applies.
