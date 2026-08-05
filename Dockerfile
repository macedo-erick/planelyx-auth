# syntax=docker/dockerfile:1

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
    KC_HTTP_RELATIVE_PATH=/auth \
    KC_HTTP_MANAGEMENT_RELATIVE_PATH=/

COPY themes/planelyx /opt/keycloak/themes/planelyx
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY realm/realm-export.json /opt/keycloak/data/import/realm-export.json

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
# --import-realm is a no-op once the realm exists in the database. It only ever runs on
# the very first boot against an empty `keycloak` DB.
CMD ["start", "--optimized", "--import-realm"]
