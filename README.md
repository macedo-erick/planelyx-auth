# auth

The shared Keycloak: one server, one realm per product, each realm reached on that product's own
domain. Holds the realm definitions, the login themes, the provisioning event listener, and the
deployment stack that runs all of it.

```
realms/          one file per realm, applied on that realm's first boot only
themes/          login themes, per product
spi/             the `planelyx-provisioning` event listener
Dockerfile       builds the optimized Keycloak image
compose.prod.yaml   the production stack — shipped to the VPS per deploy
nginx/           the admin vhost, the shared proxy snippets, the catch-all server
VPS_SETUP.md     building and operating the host
```

| Realm | Served at | Product |
|---|---|---|
| `planelyx` | `https://planelyx.com/auth` | planelyx-ui / planelyx-api / planelyx-ocr |
| `listryx` | `https://listryx.com/auth` | listryx-ui / listryx-api |

`auth.macedosoftware.com` serves the admin console and nothing else.

## One server, many hostnames

`KC_HOSTNAME` is deliberately **unset**, with `KC_HOSTNAME_STRICT=false`. Keycloak then builds
every URL it emits — the issuer, the authorization endpoint, the links in password-reset emails —
from the hostname the request arrived on. That is what lets a single container serve
`planelyx.com/auth/realms/planelyx` and, later, `<other-product>/auth/realms/<other-realm>`,
each under its own domain.

Two things follow, and both matter:

- **Each product keeps first-party cookies.** Keycloak is same-site with the app that uses it,
  so silent SSO and the session iframe work without relying on third-party cookies. A single
  shared auth domain would have broken exactly that.
- **The `Host` header decides the issuer**, so it must never be attacker-controlled. Every
  `/auth/` location includes `snippets/keycloak-proxy.conf`, which sets `Host` and
  `X-Forwarded-Host` to the vhost's own `$server_name`. This is a security control, not a
  formatting detail — `VPS_SETUP.md` §4 has the reasoning, and the deploy workflow asserts it on
  every run.

There is no SSO *between* products. Separate realms means separate user bases and separate
sessions, which is the intended isolation.

## Why an image rather than the stock one

`kc.sh build` bakes in the Postgres provider, the `/auth` relative path and the provisioning
provider so the container starts with `--optimized` and skips the build step at boot. Themes are
copied in at build time because production mode caches them — unlike the `start-dev` used
locally, a theme edit needs a rebuild here.

The `/auth` prefix is a build-time option and therefore applies to every hostname. Each product
serves Keycloak at `<its-domain>/auth/`.

## ⚠️ A realm imports exactly once

`--import-realm` is a **no-op for a realm that already exists** in the `keycloak` database, and
the `${VAR}` substitutions in a realm file only ever resolve during that first import. For an
established realm these files are documentation, not the source of truth — later changes go
through the admin console or `kcadm.sh`.

Consequences worth knowing before they surprise you:

- A client named in the file will not appear on an existing realm. Create it by hand and copy
  its generated secret out of the console.
- `eventsListeners` is not applied either. Add `planelyx-provisioning` by hand: Realm settings →
  Events → Event listeners.

If a realm comes out wrong on first boot and holds no real users yet, drop and recreate the
database so the import runs again — see `VPS_SETUP.md` §7. Once real users exist, that option is
gone.

## Realm import variables

Read only during a realm's first import:

| Variable | Local | Production | Used for |
|---|---|---|---|
| `PLANELYX_UI_ORIGIN` | `http://localhost:4200` | `https://planelyx.com` | `webOrigins` — a bare origin, no path |
| `PLANELYX_UI_BASE_URL` | `http://localhost:4200` | `https://planelyx.com/ui` | `rootUrl`, `redirectUris`, post-logout URIs — includes the base path |
| `PLANELYX_KEYCLOAK_ADMIN_CLIENT_SECRET` | `local-dev-secret` | read out of Keycloak | secret of the `planelyx-api-admin` client |
| `LISTRYX_UI_ORIGIN` | `http://localhost:4201` | `https://listryx.com` | `webOrigins` for `listryx-ui` |
| `LISTRYX_UI_BASE_URL` | `http://localhost:4201` | `https://listryx.com/ui` | `rootUrl`, `redirectUris`, post-logout URIs for `listryx-ui` |
| `LISTRYX_KEYCLOAK_ADMIN_CLIENT_SECRET` | `local-dev-secret` | read out of Keycloak | secret of the `listryx-api-admin` client |

Each product's `_ORIGIN`/`_BASE_URL` pair is split because `webOrigins` is a CORS origin and
rejects a path, while redirect URIs must carry the `/ui` base href the SPA is served under.

## Clients in the `planelyx` realm

| Client | Type | Used by |
|---|---|---|
| `planelyx-api` | public, standard flow + PKCE | the Angular app, to sign users in |
| `planelyx-api-admin` | confidential, service account only | the API, to read and update the signed-in user's own profile |

`planelyx-api-admin` holds the `realm-management` roles `view-users` and `manage-users`, granted
through the seeded `service-account-planelyx-api-admin` user in the export. The API reads its
secret from `KEYCLOAK_ADMIN_CLIENT_SECRET` and reaches the Admin API at
`https://auth.macedosoftware.com/auth` — the product domains return 404 for `/auth/admin/`.

## Clients in the `listryx` realm

| Client | Type | Used by |
|---|---|---|
| `listryx-ui` | public, standard flow + PKCE | the Angular app, to sign users in |
| `listryx-api-admin` | confidential, service account only | the API, to read and update the signed-in user's own profile |

The same pair as planelyx, and for the same reason — `listryx-api`'s `/api/me` edits the user's
own Keycloak profile — so `LISTRYX_KEYCLOAK_ADMIN_CLIENT_SECRET` must be set before the realm
first boots. Its fallback is the literal `local-dev-secret`, and a realm imports exactly once, so
an unset variable in production means a service account holding `manage-users` with a secret
published in this repository.

What listryx does not have is the provisioning listener: it seeds nothing on registration, so its
`eventsListeners` stays at `jboss-logging` and there is no callback URL or shared secret to keep
in step across repos.

## The provisioning event listener

`spi/` is a small Maven module producing `planelyx-provisioning`, an `EventListenerProvider` that
tells a product's API that a user now exists, so it can seed whatever a new account needs. It
fires on `REGISTER` and on admin `USER`/`CREATE`, posts after the transaction commits, signs the
body with HMAC-SHA256, and retries at 1s, 4s and 15s before logging the user id at ERROR.

It is built by the first stage of the `Dockerfile` and copied into `/opt/keycloak/providers/`
**before** `kc.sh build` — a jar added after that build is invisible to `start --optimized`.

Configuration is **per realm**, from plain environment variables:

| Variable | Meaning |
| --- | --- |
| `PROVISIONING_<REALM>_URL` | where to post for that realm |
| `PROVISIONING_<REALM>_SECRET` | shared with that product's API, which rejects callbacks that do not match |

`<REALM>` is the realm name upper-cased with every non-alphanumeric character replaced by `_`, so
realm `planelyx` reads `PROVISIONING_PLANELYX_URL` and `PROVISIONING_PLANELYX_SECRET`. A realm
with no pair configured never calls out; a realm with only one half of the pair logs a warning at
startup and is skipped. Either way a misconfiguration costs that realm's new users their
provisioning rather than stopping everyone on the server from signing in.

Environment variables rather than `spi-events-listener-*` options, because those sit on the
build-time/runtime boundary `start --optimized` enforces and fail the container with an exit code
rather than a message.

Keep `spi/pom.xml`'s `keycloak.version` in step with the base image in the `Dockerfile`; the jar
is compiled against one and loaded by the other.

## Local development

Each product's local stack runs its own Keycloak and mounts what it needs from **this** repo, so
every realm and theme exists once, here, and edits are live without a rebuild:

| Stack | Serves at | Mounts |
|---|---|---|
| `planelyx-api/compose.yaml` | `http://localhost:8081/auth` | builds this image (`context: ../auth`), then binds `realms/planelyx.json` and `themes/planelyx` over the baked copies |
| `listryx-api/compose.yaml` | `http://localhost:8089/auth` | the stock `keycloak:26.0` with `--http-relative-path=/auth`, binding `realms/listryx.json` and `themes/listryx` |

Listryx runs the stock image rather than building this one because it uses neither the
provisioning SPI nor the Postgres provider; the only build-time option it needs is the `/auth`
path, and `start-dev` takes that as a flag. Its compose reaches in with `../../planelyx/auth` —
this repository is shared, and only happens to be checked out beside planelyx.

Locally Keycloak serves on a fixed hostname — the dynamic resolution above is a production
concern. There is no seeded user on either realm; registration is enabled, so create an account
through the app.

## Deploying

A push to `master` deploys itself. One workflow does the whole thing: a `build` job pushes
`keycloak:<sha>` to Artifact Registry, and a `deploy` job that `needs` it ships
`compose.prod.yaml`, renders `.env` on the VPS from this repo's secrets, pulls, brings the stack
up and verifies the issuer on every hostname. There is no `AUTH_TAG` to copy anywhere — the
commit being deployed *is* the tag.

Rollback is the same workflow with a tag: Actions → deploy → Run workflow, `auth_tag` =
the commit SHA you want back. A non-empty `auth_tag` skips the build entirely and deploys the
image already in Artifact Registry, checking out that same commit so its `compose.prod.yaml`
goes with it. Leave `auth_tag` blank and it builds the branch, exactly as a push does. The tag a
run replaced is in `.env.prev` on the box.

The other input, `allow_secret_change`, is for a credential rotation the drift check would
otherwise reject.

Three of those secrets also live in a product's infra repo, and nothing checks the two copies
agree — `KEYCLOAK_ADMIN_CLIENT_SECRET` and `PLANELYX_PROVISIONING_SECRET` in `planelyx-infra`,
and `LISTRYX_KEYCLOAK_ADMIN_CLIENT_SECRET` in `listryx-infra`, where it is named
`KEYCLOAK_ADMIN_CLIENT_SECRET` because that API only knows its own realm. Rotating any of them
means updating both repositories and redeploying both stacks. `VPS_SETUP.md` §6 has the full
table.
