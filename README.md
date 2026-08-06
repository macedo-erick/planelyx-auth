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

Runtime env vars are set by `compose.prod.yaml` in `planelyx-infra`. Three matter for the
realm import:

| Variable | Local | Production | Used for |
|---|---|---|---|
| `PLANELYX_UI_ORIGIN` | `http://localhost:4200` | `https://planelyx.com` | `webOrigins` — must be a bare origin, no path |
| `PLANELYX_UI_BASE_URL` | `http://localhost:4200` | `https://planelyx.com/ui` | `rootUrl`, `redirectUris`, post-logout URIs — includes the base path |
| `PLANELYX_KEYCLOAK_ADMIN_CLIENT_SECRET` | `local-dev-secret` | *(generate one)* | secret of the `planelyx-api-admin` client |

The first two are split because `webOrigins` is a CORS origin and rejects a path, while
redirect URIs must carry the `/ui` base href the SPA is served under.

## Clients

| Client | Type | Used by |
|---|---|---|
| `planelyx-api` | public, standard flow + PKCE | the Angular app, to sign users in |
| `planelyx-api-admin` | confidential, service account only | the API, to read and update the signed-in user's own profile |

`planelyx-api-admin` holds the `realm-management` roles `view-users` and `manage-users`,
granted through the seeded `service-account-planelyx-api-admin` user in the export. The API
reads its secret from `KEYCLOAK_ADMIN_CLIENT_SECRET`; without it `GET`/`PUT /api/me` fail and
the app's profile page cannot load.

**On an existing realm this client will not appear** — see the warning below. Create it by
hand and copy its generated secret into the API's environment.

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

## This repo is the only Keycloak configuration

Local development builds **this image** — `planelyx-api/compose.yaml` has
`build: { context: ../planelyx-auth }` — so the realm, the login theme and the provisioning
provider exist once, here, and are used by both environments. `planelyx-api/docker/keycloak/`
used to hold a second copy of the realm and theme; it was deleted because the two drifted.

Two consequences for local development:

- Keycloak serves under `/auth` there as well, because `KC_HTTP_RELATIVE_PATH` is a build option
  baked in below.
- There is no seeded user. Registration is enabled; create an account through the app.

`compose.yaml` bind-mounts `realm/realm-export.json` and `themes/planelyx` from this repo over the
baked copies, so edits are live without a rebuild. The same files, not copies.

## The provisioning event listener

`spi/` is a small Maven module producing `planelyx-provisioning`, an `EventListenerProvider` that
tells planelyx-api a user now exists so it can seed that user's default categories. It fires on
`REGISTER` and on admin `USER`/`CREATE`, posts after the transaction commits, signs the body with
HMAC-SHA256, and retries at 1s, 4s and 15s before logging the user id at ERROR.

It is built by the first stage of the `Dockerfile` and copied into `/opt/keycloak/providers/`
**before** `kc.sh build` — a jar added after that build is invisible to `start --optimized`.
Building it here rather than in planelyx-api is what keeps it cheap: `release.yml` checks out only
this repo, so a provider living anywhere else would have to be published to a registry first.

Two things configure it, both plain environment variables read at runtime:

| Variable | Meaning |
| --- | --- |
| `PLANELYX_PROVISIONING_URL` | where to post, e.g. `http://api:8080/internal/keycloak/user-registered` |
| `PLANELYX_PROVISIONING_SECRET` | shared with the API, which rejects callbacks that do not match |

If either is unset the listener logs a warning and does nothing, so a misconfiguration costs new
users their default categories rather than stopping everyone from signing in.

**The realm must also name it.** `realm/realm-export.json` lists `planelyx-provisioning` under
`eventsListeners`, but that only applies to a realm being created — see the import caveat above. On
an existing realm, add it once by hand: Realm settings → Events → Event listeners.

Keep `spi/pom.xml`'s `keycloak.version` in step with the base image in the `Dockerfile`; the jar is
compiled against one and loaded by the other.
