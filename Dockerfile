# syntax=docker/dockerfile:1

# The event listener that tells planelyx-api a user has registered. Built here rather than
# pulled from a registry: release.yml checks out only this repo, so a provider living anywhere
# else would need publishing first. Keep spi/pom.xml's keycloak.version in step with the base
# image below — the jar is compiled against one and loaded by the other.
FROM maven:3.9-eclipse-temurin-21 AS spi
WORKDIR /spi
# Dependencies resolve from the POM alone, so this layer survives every source-only change.
COPY spi/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY spi/src ./src
RUN mvn -B -q package

# Pinned to the same 26.0 line the local compose.yaml already runs, so production is not
# also a version bump. Bump deliberately, and re-test the login theme when you do.
FROM quay.io/keycloak/keycloak:26.0 AS builder

# All build-time options. `start --optimized` exits 2 if any of these is given a *different*
# value at runtime, so they must be set here and not in compose.
#
# KC_HTTP_MANAGEMENT_RELATIVE_PATH is the subtle one: the management interface otherwise
# inherits KC_HTTP_RELATIVE_PATH, which would move the health probe to
# :9000/auth/health/ready. Pinning it to / keeps health at :9000/health/ready.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/auth \
    KC_HTTP_MANAGEMENT_RELATIVE_PATH=/

COPY themes/planelyx /opt/keycloak/themes/planelyx
# Must land before `kc.sh build`: the build is what discovers providers and bakes them into the
# optimized image. A jar added afterwards is simply not there as far as `start --optimized` is
# concerned, and the realm's Event listeners entry then points at nothing.
COPY --from=spi /spi/target/planelyx-provisioning.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY realms/ /opt/keycloak/data/import/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
# --import-realm is a no-op for a realm that already exists in the database. It only ever
# applies to a realm the `keycloak` DB has never seen.
CMD ["start", "--optimized", "--import-realm"]
