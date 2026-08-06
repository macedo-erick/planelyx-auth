package com.planelyx.keycloak;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers the provisioning listener under the id {@code planelyx-provisioning}. That string is
 * what has to appear in the realm's Event listeners for any of this to run — and because
 * {@code --import-realm} skips a realm that already exists, adding it to the realm export only
 * takes effect on a realm built from scratch. An established realm has to be told once, by hand.
 *
 * <p>Configuration comes from plain environment variables rather than {@code spi-events-listener-*}
 * options. Those options sit on the build-time/runtime boundary that {@code start --optimized}
 * enforces, and getting them on the wrong side of it fails the container with an exit code rather
 * than a message. Every other value the production stack passes to Keycloak is already an
 * environment variable.
 */
public class ProvisioningEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOG = Logger.getLogger(ProvisioningEventListenerProviderFactory.class);

    private static final String ID = "planelyx-provisioning";

    private static final String URL_ENV = "PLANELYX_PROVISIONING_URL";

    private static final String SECRET_ENV = "PLANELYX_PROVISIONING_SECRET";

    /** Null when the listener is unconfigured, which makes every callback a no-op. */
    private ProvisioningNotifier notifier;

    @Override
    public void init(Config.Scope config) {
        String url = System.getenv(URL_ENV);
        String secret = System.getenv(SECRET_ENV);

        // Deliberately not fatal. A missing variable should cost new users their default categories,
        // not stop everyone from signing in.
        if (isBlank(url) || isBlank(secret)) {
            LOG.warnf("%s and %s are not both set — user provisioning callbacks are disabled", URL_ENV, SECRET_ENV);
            return;
        }

        notifier = new ProvisioningNotifier(url, secret);
        LOG.infof("User provisioning callbacks enabled, posting to %s", url);
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return notifier != null ? new ProvisioningEventListenerProvider(session, notifier) : NoOp.INSTANCE;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do; the notifier is fully built in init.
    }

    @Override
    public void close() {
        if (notifier != null) {
            notifier.close();
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Used when the listener is enabled on the realm but not configured on the server. */
    private enum NoOp implements EventListenerProvider {
        INSTANCE;

        @Override
        public void onEvent(org.keycloak.events.Event event) {}

        @Override
        public void onEvent(org.keycloak.events.admin.AdminEvent event, boolean includeRepresentation) {}

        @Override
        public void close() {}
    }
}
