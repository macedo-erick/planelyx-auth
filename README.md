# planelyx-auth

Keycloak image for Planelyx: the realm definition plus the custom `planelyx` login theme,
baked into a production-mode image.

Deployed at `https://planelyx.com/auth` — see `DEPLOYMENT.md` in `planelyx-infra` for the
full runbook.

## Why an image rather than the stock one

`kc.sh build` bakes in the Postgres provider and the `/auth` relative path so the container
can start with `--optimized` and skip the build step at boot. The theme is copied in at
build time because production mode caches themes — unlike the `start-dev` used locally,
theme edits need a rebuild here.

## Configuration

Runtime env vars are set by `compose.prod.yaml` in `planelyx-infra`. Two matter for the
realm import:

| Variable | Local | Production | Used for |
|---|---|---|---|
| `PLANELYX_UI_ORIGIN` | `http://localhost:4200` | `https://planelyx.com` | `webOrigins` — must be a bare origin, no path |
| `PLANELYX_UI_BASE_URL` | `http://localhost:4200` | `https://planelyx.com/ui` | `rootUrl`, `redirectUris`, post-logout URIs — includes the base path |

They are split because `webOrigins` is a CORS origin and rejects a path, while redirect
URIs must carry the `/ui` base href the SPA is served under.

## ⚠️ The realm imports exactly once

`--import-realm` is a **no-op if the realm already exists** in the `keycloak` database, and
the `${VAR}` substitutions above only ever resolve during that first import. After the
first boot this file is documentation, not the source of truth — later changes go through
the admin console or `kcadm.sh`.

If the realm comes out wrong on first boot and no real users exist yet, the cheapest fix is
to drop and recreate the database:

```sql
DROP DATABASE keycloak;
CREATE DATABASE keycloak OWNER keycloak;
```

Once real users exist, that option is gone.

## Differences from the local dev realm

`planelyx-api/docker/keycloak/realm-export.json` still drives local development. This copy
differs deliberately:

- `sslRequired`: `none` → `external`
- the seeded `demo` / `Demo@Fintrack1` user is **removed**
- redirect URIs are driven by `PLANELYX_UI_BASE_URL` so they can carry the `/ui` prefix

The theme under `themes/` is currently a copy of the one in `planelyx-api/docker/keycloak/`.
Two copies will drift — collapse them once local dev points at this image.
