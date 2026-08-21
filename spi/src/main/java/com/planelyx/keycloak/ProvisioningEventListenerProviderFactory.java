package com.planelyx.keycloak;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers the provisioning listener under the id {@code planelyx-provisioning}. That string is
 * what has to appear in a realm's Event listeners for any of this to run — and because
 * {@code --import-realm} skips a realm that already exists, adding it to a realm export only
 * takes effect on a realm built from scratch. An established realm has to be told once, by hand.
 *
 * <p>One server now serves a realm per product, so the callback target is per realm rather than
 * per server: {@code PROVISIONING_<REALM>_URL} and {@code PROVISIONING_<REALM>_SECRET}, where
 * {@code <REALM>} is the realm name upper-cased with every non-alphanumeric character replaced
 * by an underscore. A realm with no pair configured never calls out.
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

    private static final Pattern URL_ENV = Pattern.compile("^PROVISIONING_(.+)_URL$");

    private static final String SECRET_ENV_FORMAT = "PROVISIONING_%s_SECRET";

    /** Keyed by {@link #realmKey(String)}. Empty when nothing is configured, which makes every callback a no-op. */
    private Map<String, ProvisioningNotifier> notifiers = Collections.emptyMap();

    @Override
    public void init(Config.Scope config) {
        Map<String, ProvisioningNotifier> built = new LinkedHashMap<>();

        // Sorted so the startup log lists the realms in a stable order.
        for (String name : new TreeSet<>(System.getenv().keySet())) {
            Matcher matcher = URL_ENV.matcher(name);

            if (!matcher.matches()) {
                continue;
            }

            String key = matcher.group(1);
            String url = System.getenv(name);
            String secret = System.getenv(String.format(SECRET_ENV_FORMAT, key));

            // Deliberately not fatal. A half-configured realm should cost its new users their
            // default categories, not stop everyone on the server from signing in.
            if (isBlank(url) || isBlank(secret)) {
                LOG.warnf("PROVISIONING_%s_URL and PROVISIONING_%s_SECRET are not both set — "
                        + "provisioning callbacks are disabled for that realm", key, key);
                continue;
            }

            built.put(key, new ProvisioningNotifier(url, secret));
            LOG.infof("User provisioning callbacks enabled for realm key %s, posting to %s", key, url);
        }

        if (built.isEmpty()) {
            LOG.warn("No PROVISIONING_<REALM>_URL/_SECRET pair is set — user provisioning callbacks are disabled");
        }

        notifiers = built;
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return notifiers.isEmpty() ? NoOp.INSTANCE : new ProvisioningEventListenerProvider(session, notifiers);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do; the notifiers are fully built in init.
    }

    @Override
    public void close() {
        notifiers.values().forEach(ProvisioningNotifier::close);
    }

    @Override
    public String getId() {
        return ID;
    }

    /** {@code planelyx} -> {@code PLANELYX}, so a realm name maps onto one environment variable name. */
    static String realmKey(String realmName) {
        return realmName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Used when the listener is enabled on a realm but nothing is configured on the server. */
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
