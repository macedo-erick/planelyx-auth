# VPS setup — the auth stack

Building and operating the Keycloak host. This is a runbook: each section records the failure
mode as well as the happy path, because the failures here are the ones that cost real time.

Keycloak serves **one realm per product**, and each realm is reached on **that product's own
domain** — `https://planelyx.com/auth/realms/planelyx` today, `https://<product>/auth/realms/<realm>`
for everything added later. There is no single "auth domain" in front of the products;
`auth.macedosoftware.com` exists only to administer the server.

That works because `KC_HOSTNAME` is unset and `KC_HOSTNAME_STRICT=false`, so Keycloak builds
every URL it emits — issuer, authorization endpoint, password-reset links — from the hostname
the request arrived on. The consequence is §4, and it is the one thing on this page that is a
security control rather than a convenience.

---

## 1. Prerequisites

This stack shares a host with the Planelyx stack and assumes it is already built. From
`planelyx-infra/VPS_SETUP.md`:

- a hardened Ubuntu host with a deploy user and SSH keys (§1-4)
- Docker Engine and the Compose plugin (§5)
- host nginx on 443, and certbot with its webroot at `/var/www/certbot` (§9-10)
- host PostgreSQL 16 on `:5432`, listening on the Docker bridge address (§7)

Nothing below re-creates those. If you are building a host from bare Ubuntu, do that document
first, then this one.

The two stacks are independent Compose projects on one box:

```
~/planelyx-infra/   compose project `planelyx`   net 172.20.0.0/16   ui, api, ocr
~/auth/             compose project `auth`       net 172.21.0.0/16   keycloak
```

---

## 2. PostgreSQL

Keycloak owns a database of its own, with a role of its own. It holds the `credential` table —
every product's password hashes — so a compromise of any application service must not carry a
credential that can read it.

### Fresh host

```sql
CREATE ROLE keycloak LOGIN PASSWORD '<kc-password>';
CREATE DATABASE keycloak OWNER keycloak;
```

### Existing host, migrating off the planelyx stack

The role and database already exist and **must not be touched** — they hold every user account.
Only the network they are reached from changes, because a second Compose project means a second
bridge network with a subnet of its own.

Two layers restrict port 5432, and **both** name a subnet. `planelyx-infra/VPS_SETUP.md` §4
and §7 opened them for `172.20.0.0/16`; neither knows about this stack's network.

First `ufw`, which is the one that bites, because it fails silently:

```bash
sudo ufw allow from 172.21.0.0/16 to any port 5432 proto tcp \
    comment 'auth containers -> host postgres'
sudo ufw reload
sudo ufw status verbose | grep 5432
```

Then `/etc/postgresql/16/main/pg_hba.conf`, alongside the existing `172.20.0.0/16` rules —
**add** a line, do not edit the existing `keycloak` one, or the §8 rollback to the old container
stops working:

```
host    keycloak    keycloak    172.21.0.0/16    scram-sha-256
```

```bash
sudo systemctl reload postgresql
```

The `172.21.0.0/16` in both places and the `ipam` subnet in `compose.prod.yaml` must agree.

The two failures look nothing alike, and telling them apart is most of the diagnosis:

| Missing | What Keycloak logs | Why |
|---|---|---|
| the `ufw` rule | `The connection attempt failed`, then `Acquisition timeout while waiting for new connection` — nothing naming Postgres | `default deny incoming` **drops** the packets, so the TCP handshake gets no answer at all |
| the `pg_hba` line | an immediate `FATAL: no pg_hba.conf entry for host "172.21.0.2"` | Postgres accepted the connection and then refused it, by name |

A timeout is the firewall. An error that names the address is `pg_hba.conf`. Fix `ufw` first:
until the packets arrive, `pg_hba.conf` is never consulted and a correct rule there proves
nothing.

Verify before starting the stack:

```bash
docker run --rm --network auth_auth postgres:16 \
  psql 'postgresql://keycloak:<kc-password>@172.21.0.1:5432/keycloak' -c 'select 1'
```

The old `172.20.0.0/16` rule can be dropped once the `auth` service is gone from the planelyx
stack — not before, or the rollback path in §8 stops working.

---

## 3. DNS and TLS

`auth.macedosoftware.com` is the admin hostname. The product domains already resolve to this
box and need no new records.

1. `A auth.macedosoftware.com` → the VPS address, same as `monitoring.macedosoftware.com`.
2. Install the port-80 half of `nginx/auth.conf` **first** and reload. Certbot validates over
   HTTP, so the vhost has to be answering on 80 and serving `/.well-known/acme-challenge/`
   before a certificate exists. Installing the whole file first fails `nginx -t`, because the
   443 block references a certificate that is not there yet.
3. ```bash
   sudo certbot certonly --webroot -w /var/www/certbot -d auth.macedosoftware.com
   ```
4. Install the rest of the file, `sudo nginx -t && sudo systemctl reload nginx`.

---

## 4. nginx

Three files, all into the host nginx tree:

```bash
sudo cp nginx/snippets/keycloak-proxy.conf /etc/nginx/snippets/
sudo cp nginx/auth.conf                    /etc/nginx/sites-available/auth
sudo cp nginx/catch-all.conf               /etc/nginx/sites-available/catch-all
sudo ln -s /etc/nginx/sites-available/auth      /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/catch-all /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

### Hand the three files to the deploy user

That is the one-time bootstrap. From there the **deploy workflow owns these three files** and
rewrites them on every run, so the deploy user has to be able to write them without `sudo`:

```bash
sudo chown "$VPS_USER":"$VPS_USER" /etc/nginx/sites-available/auth \
                                   /etc/nginx/sites-available/catch-all \
                                   /etc/nginx/snippets/keycloak-proxy.conf
sudo chmod 644 /etc/nginx/sites-available/auth \
               /etc/nginx/sites-available/catch-all \
               /etc/nginx/snippets/keycloak-proxy.conf
```

`$VPS_USER` is the SSH user GitHub Actions deploys as. The directories stay root-owned and the
symlinks in `sites-enabled/` are never touched — only these three files change hands, so the
pipeline can replace them and nothing else in `/etc/nginx`.

The workflow copies an explicit list of three files rather than syncing the directory, so
`planelyx-infra`'s `sites-available/planelyx` and `snippets/planelyx-proxy.conf` are left alone.
A directory sync in either repo would delete the other's files, and the next `nginx -t` would
fail on the dangling include.

### Sudoers rule

The pipeline needs exactly two privileged commands:

```bash
sudo visudo -f /etc/sudoers.d/auth-deploy
```

```
<VPS_USER> ALL=(root) NOPASSWD: /usr/sbin/nginx -t, /bin/systemctl reload nginx
```

Deliberately not blanket `NOPASSWD`: a leaked deploy key then buys a config test and a reload,
not root. Check the binary paths with `command -v nginx` and `command -v systemctl` — a path that
does not match is a rule that silently never applies. Verify **as the deploy user**,
non-interactively, or the failure surfaces mid-deploy instead:

```bash
sudo -n nginx -t && echo "sudoers rule works"
```

`planelyx-infra` installs `/etc/sudoers.d/planelyx-deploy` with the same two commands. Both files
can coexist; if the deploy user is the same for both, one rule is enough and the second is a
harmless duplicate.

### The Host header is a security control here

Keycloak derives the issuer, and the base URL of every password-reset and email-verification
link, from the `Host` / `X-Forwarded-Host` it receives. The general-purpose proxy snippet
forwards the client's own value (`proxy_set_header Host $host`), which is right for an
application and wrong for this: a request carrying `Host: attacker.example` would produce reset
links pointing at the attacker's domain, and tokens issued under an issuer nobody validates.

`snippets/keycloak-proxy.conf` is the fix, and **every** `/auth/` location on every domain must
include it *instead of* a general-purpose proxy snippet — not alongside one:

```nginx
    location /auth/ {
        proxy_pass http://127.0.0.1:8085;
        include /etc/nginx/snippets/keycloak-proxy.conf;
    }
```

It is a complete set of proxy headers, not an overlay: it sets `Host` and `X-Forwarded-Host` to
`$server_name` — the literal first name of the matching `server` block, which comes from your
config and not from the request — and carries the oversized proxy buffers Keycloak's headers
need. Without those buffers, login works and the admin console fails with an upstream header
error at unpredictable moments.

Self-contained on purpose. Including the general snippet and then overriding the two headers
works, but it depends on directive order inside the location and makes `nginx -t` warn about
the proxy header hash. One file, one full set.

`catch-all.conf` is the second layer: a `default_server` on both ports so a request whose `Host`
matches no vhost is refused outright rather than falling through to whichever block nginx
happened to define first. Note that it returns 444 on port 80, so when you add the **next**
product domain, its ACME challenge will not be answered until that domain's own port-80 vhost
is installed and enabled.

The smoke checks in `.github/workflows/deploy.yml` assert both halves of this on every deploy:
that each hostname produces its own issuer, and that a forged `Host` produces none.

### Adding a product domain

In that product's own vhost, and nothing in this repo changes:

```nginx
    location /auth/ {
        proxy_pass http://127.0.0.1:8085;
        include /etc/nginx/snippets/keycloak-proxy.conf;
    }

    location /auth/admin/ { return 404; }
```

The second line keeps the admin console off the product domain — it belongs on
`auth.macedosoftware.com`. It also blocks the Admin REST API on that host, so a service that
calls the Admin API must point at `https://auth.macedosoftware.com/auth`, as `planelyx-api`'s
`KEYCLOAK_SERVER_URL` does.

---

## 5. Artifact Registry

The image lives in its own repository, `auth`, not in `planelyx`:

```
southamerica-east1-docker.pkg.dev/<project>/auth/keycloak:<sha>
```

Repository-level IAM does not inherit from another repository, so the grants must be made
against `auth` explicitly:

```bash
gcloud artifacts repositories add-iam-policy-binding auth \
  --location=southamerica-east1 \
  --member="serviceAccount:<release-sa>@<project>.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.writer

gcloud artifacts repositories add-iam-policy-binding auth \
  --location=southamerica-east1 \
  --member="serviceAccount:<vps-pull-sa>@<project>.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.reader
```

Skip the writer grant and the first build fails at push with
`denied: Permission "artifactregistry.repositories.uploadArtifacts" denied`. Skip the reader
grant and the build succeeds while the deploy fails at `docker compose pull`.

On the box, if the VPS pulls with a different service account than the planelyx images use:

```bash
cat sa-key.json | docker login -u _json_key --password-stdin \
  https://southamerica-east1-docker.pkg.dev
```

---

## 6. GitHub secrets and variables

None of this carries over from `planelyx-infra` — a secret is scoped to its repository. Add all
of it to **this** repo before the first deploy run: Settings → Secrets and variables → Actions.

### Secrets

| Secret | Where it comes from |
|---|---|
| `GCP_PROJECT_ID` | the GCP project number/id; same value as `planelyx-infra` |
| `GCP_SA_KEY` | the build service account's JSON key, granted writer on the `auth` repository (§5) |
| `VPS_HOST` | the VPS address; same as `planelyx-infra` |
| `VPS_USER` | the deploy user; same as `planelyx-infra` |
| `VPS_SSH_KEY` | the deploy private key; same as `planelyx-infra` |
| `VPS_SSH_KNOWN_HOSTS` | `ssh-keyscan <host>` output; same as `planelyx-infra` |
| `KC_DB_PASSWORD` | the `keycloak` Postgres role's password — **move** it out of `planelyx-infra` |
| `KC_ADMIN` | bootstrap admin username — **move** it out of `planelyx-infra` |
| `KC_ADMIN_PASSWORD` | bootstrap admin password — **move** it out of `planelyx-infra` |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | **shared with `planelyx-infra`, must be identical** |
| `PLANELYX_PROVISIONING_SECRET` | **shared with `planelyx-infra`, must be identical** |

### Variables

| Variable | Default | Notes |
|---|---|---|
| `AUTH_LOG_LEVEL` | `info` | one of `all trace debug info warn error fatal off`; `deploy.yml` rejects anything else. A runtime option, so raising it needs no image rebuild — and Loki retention is 30 days, which `debug` on this service eats far sooner |

### The shared pair

Two secrets exist in both repositories and **nothing checks that the two copies agree**. Each
repo's deploy workflow renders its own `.env` from its own secrets, and a mismatch fails late
and quietly:

- `KEYCLOAK_ADMIN_CLIENT_SECRET` — the confidential `planelyx-api-admin` client. Keycloak holds
  the real value; `planelyx-api` presents it. A mismatch is a 401 on the profile page and
  nowhere else.
- `PLANELYX_PROVISIONING_SECRET` — the HMAC key on the "a user registered" callback. Keycloak
  signs, `planelyx-api` verifies. A mismatch means new users silently get no default categories.

Rotating either means updating both repositories and redeploying both stacks. Between the two
deploys the feature is broken, so do them back to back.

`KEYCLOAK_ADMIN_CLIENT_SECRET` is also the one credential here that is **not** generated: it is
read out of Keycloak (Clients → `planelyx-api-admin` → Credentials), because the realm export
only ever applies on a realm's first import. Generating a fresh value in GitHub does not change
what Keycloak expects.

### After the cutover

Delete `KC_DB_PASSWORD`, `KC_ADMIN` and `KC_ADMIN_PASSWORD` from `planelyx-infra` — that repo no
longer renders them. Keep `KEYCLOAK_ADMIN_CLIENT_SECRET` and `PLANELYX_PROVISIONING_SECRET`
there; `planelyx-api` still needs both.

---

## 7. First boot

`--import-realm` applies a file from `realms/` only to a realm the database has never seen. On
an established database it is a no-op, every time, for every file. That single fact explains
most of the surprises in this section.

Watch it:

```bash
cd ~/auth
docker compose -f compose.prod.yaml logs -f keycloak
```

What to expect on an **empty** database: an `Importing realm planelyx` line per file in
`realms/`, the Quarkus augmentation banner, then `Listening on: http://0.0.0.0:8080`. The
healthcheck takes up to 45s to go green — `start_period` in the Compose file covers that.

What to expect on the **existing** database: no import lines at all, which is correct.

Things that only bite on a fresh realm:

- **The `planelyx-provisioning` event listener** has to be switched on for the realm by hand if
  the realm already existed: admin console → Realm settings → Events → Event listeners → add
  `planelyx-provisioning`. The export names it, and the import skipped the realm.
- **`planelyx-api-admin`'s secret** is substituted from `PLANELYX_KEYCLOAK_ADMIN_CLIENT_SECRET`
  at import. On an existing realm the client already has a secret; read it out of the console
  rather than trying to set it from the environment.
- **The `firstName` / `lastName` trap.** Keycloak 26 marks both required by default. A user
  created without them cannot get a token: the request fails with
  `invalid_grant / "Account is not fully set up"`, which reads like a credential problem and is
  not one.

If a fresh import went wrong and the realm is worth nothing yet, the escape hatch is to drop and
recreate the database so the import runs again:

```bash
docker compose -f compose.prod.yaml down
sudo -u postgres psql -c 'DROP DATABASE keycloak;'
sudo -u postgres psql -c 'CREATE DATABASE keycloak OWNER keycloak;'
docker compose -f compose.prod.yaml up -d --wait
```

This destroys every user account on the server. It is a first-boot tool and nothing else.

---

## 8. Verification

Run all of it after the first deploy, and after any change to the nginx vhosts.

1. The container is healthy:
   ```bash
   docker compose -f compose.prod.yaml ps
   ```
2. Each product domain issues under its own name:
   ```bash
   curl -s https://planelyx.com/auth/realms/planelyx/.well-known/openid-configuration | jq -r .issuer
   # -> https://planelyx.com/auth/realms/planelyx
   ```
3. The admin hostname issues under its own name:
   ```bash
   curl -s https://auth.macedosoftware.com/auth/realms/planelyx/.well-known/openid-configuration | jq -r .issuer
   # -> https://auth.macedosoftware.com/auth/realms/planelyx
   ```
4. A forged `Host` does **not** become the issuer:
   ```bash
   curl -s -H 'Host: forged.example' \
     https://planelyx.com/auth/realms/planelyx/.well-known/openid-configuration | jq -r .issuer
   ```
   Anything mentioning `forged.example` means §4 is not in place.
5. The same string is seen from inside an application container:
   ```bash
   cd ~/planelyx-infra
   docker compose -f compose.prod.yaml exec api \
     curl -s https://planelyx.com/auth/realms/planelyx/.well-known/openid-configuration | jq -r .issuer
   ```
   This is the `extra_hosts` hairpin working. The API validates JWTs by issuer alone, so this
   string and the browser's must be identical.
6. The admin console answers on the admin hostname and nowhere else:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://auth.macedosoftware.com/auth/admin/   # 200 or 302
   curl -s -o /dev/null -w '%{http_code}\n' https://planelyx.com/auth/admin/              # 404
   ```
7. Log in to Planelyx, then hard-reload the page — the session must survive. Keycloak is on the
   product's own domain, so its cookies are first-party and silent SSO works.
8. Register a new user, then confirm they land with default categories. The auth log should show
   `posting to https://planelyx.com/internal/keycloak/user-registered`, and the planelyx nginx
   access log a request from a `172.21.x.x` address.
9. Request a password reset and check the link in the email points at `planelyx.com`, not at
   `auth.macedosoftware.com`. This is dynamic hostname resolution doing the thing it exists for.
10. The callback endpoint is not reachable from outside:
    ```bash
    curl -s -o /dev/null -w '%{http_code}\n' https://planelyx.com/internal/keycloak/user-registered  # 403
    ```

---

## 9. Operations

### Backups

The `keycloak` database is now the only copy of every product's user accounts and password
hashes, which makes it the most valuable state on the box. It is also the only state this stack
has — the container is disposable and holds nothing.

```bash
sudo -u postgres pg_dump -Fc keycloak > keycloak-$(date +%F).dump
```

Restore into an empty database:

```bash
docker compose -f compose.prod.yaml down
sudo -u postgres psql -c 'DROP DATABASE keycloak;'
sudo -u postgres psql -c 'CREATE DATABASE keycloak OWNER keycloak;'
sudo -u postgres pg_restore -d keycloak keycloak-<date>.dump
docker compose -f compose.prod.yaml up -d --wait
```

Take a dump before every Keycloak **major** version bump. The schema migration on first boot is
one-way, and an older image will not start against a migrated database.

### Deploying

Merging to `master` is the whole deploy: `deploy.yml` builds the image in its `build` job and
deploys it from `deploy`, unattended, with the commit SHA as the image tag. Nothing is dispatched
by hand and no tag is passed between workflows.

One thing switches that off without any error appearing: if the `production` environment has
required reviewers, the run starts but parks on approval, which reads as "it deployed by itself,
eventually". Settings → Environments → production.

Rollback is Actions → deploy → Run workflow with `auth_tag` set to the commit SHA you want
back. A non-empty `auth_tag` skips the `build` job and deploys the image already in the registry,
so a rollback neither rebuilds nor depends on the Dockerfile still producing the same output. The
tag the last run replaced is in `.env.prev` on the box, and the run summary records whether the
image was built or redeployed.

Because the deploy job checks out `auth_tag` too, rolling back the image rolls back
`compose.prod.yaml` **and the three nginx files** with it. That is what you want, and it means an
image is only ever deployed against the Compose file and the vhost from its own commit.

The nginx step runs after the containers are up and before the smoke checks. It compares each
shipped file against the live one and does nothing at all when they match, so an ordinary deploy
does not reload nginx. When something did change it backs the live copies up into `~/auth/nginx-prev/`,
writes the new ones, and reloads only if `sudo nginx -t` passes — a failing test restores the
backups and fails the run without ever reloading. That matters because this nginx process also
serves `planelyx.com` and `monitoring.macedosoftware.com`; the smoke checks end by re-fetching
`monitoring.macedosoftware.com` for exactly that reason.

Break-glass, on the VPS:

```bash
cd ~/auth
docker compose -f compose.prod.yaml pull
docker compose -f compose.prod.yaml up -d --wait --wait-timeout 300
```

The next workflow run reconciles `.env` against the repo secrets and overwrites any hand edit,
including a hand-edited `AUTH_TAG`.

### Restart ordering

`planelyx-api` resolves its issuer eagerly at startup and no longer has a `depends_on` edge to
Keycloak — they are separate Compose projects now. After a host reboot the API may restart-loop
until this stack is healthy. That resolves itself; `restart: unless-stopped` keeps retrying. If
it does not, this stack is the thing to look at first.

---

## 10. Adding a product

1. A realm file in `realms/`, named for the realm. It only ever applies to a realm the database
   has never seen, so it is the *creation* of that realm and nothing else.
2. `PROVISIONING_<REALM>_URL` and `PROVISIONING_<REALM>_SECRET` in `compose.prod.yaml` and the
   deploy workflow, if that product wants the "a user registered" callback. `<REALM>` is the
   realm name upper-cased with every non-alphanumeric character replaced by `_`. A realm with no
   pair configured simply never calls out.
3. A `location /auth/` in that product's own vhost, per §4, plus `location /auth/admin/ { return
   404; }`.
4. A line in `ISSUER_CHECKS` in `.github/workflows/deploy.yml` — `<origin> <realm>` — so every
   deploy asserts that domain issues for that realm and no other.
5. That product's services point at `https://<its-domain>/auth/realms/<realm>` as the issuer,
   and at `https://auth.macedosoftware.com/auth` if they need the Admin API.

Nothing in this stack's hostname configuration or Compose topology changes to add a product.
