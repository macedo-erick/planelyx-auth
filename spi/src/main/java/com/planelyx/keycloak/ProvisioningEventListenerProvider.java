package com.planelyx.keycloak;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Turns "a user was created" into a single call to planelyx-api.
 *
 * Two paths reach here, because there are two ways a user comes into existence: {@code REGISTER}
 * for someone signing up through the login theme, and an admin {@code USER}/{@code CREATE} event
 * for someone added through the admin console or the Admin API. A user imported with the realm
 * produces neither — realm import emits no events at all — which is why the realm export ships no
 * pre-made accounts.
 *
 * The call is enlisted after transaction completion rather than made inline. Keycloak fires
 * {@code onEvent} while the registration is still open, so sending immediately would tell the API
 * about users whose creation then rolled back, and would put an HTTP round trip inside the
 * transaction that the browser is waiting on.
 */
final class ProvisioningEventListenerProvider implements EventListenerProvider {

    private final KeycloakSession session;

    private final ProvisioningNotifier notifier;

    ProvisioningEventListenerProvider(KeycloakSession session, ProvisioningNotifier notifier) {
        this.session = session;
        this.notifier = notifier;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.REGISTER) {
            return;
        }

        notifyAfterCommit(event.getUserId(), event.getRealmId());
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() != ResourceType.USER || event.getOperationType() != OperationType.CREATE) {
            return;
        }

        notifyAfterCommit(userIdFrom(event.getResourcePath()), event.getRealmId());
    }

    private void notifyAfterCommit(String userId, String realmId) {
        if (userId == null) {
            return;
        }

        // Resolved here rather than inside the callback: by the time the transaction has completed
        // the session is on its way out and is no longer safe to read from.
        String realmName = realmName(realmId);

        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                notifier.userRegistered(userId, realmName);
            }

            @Override
            protected void rollbackImpl() {
                // The user does not exist, so there is nothing to provision.
            }
        });
    }

    /**
     * Realm ids are opaque — a name for realms created by hand, a UUID for most others — and the
     * API wants the name it knows the realm by.
     */
    private String realmName(String realmId) {
        RealmModel realm = session.realms().getRealm(realmId);

        return realm != null ? realm.getName() : realmId;
    }

    /** Admin events carry the subject as a path, {@code users/<id>}. */
    private static String userIdFrom(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }

        String[] segments = resourcePath.split("/");

        // Anything longer is a sub-resource of the user (role mappings, credentials), not a create.
        return segments.length == 2 && "users".equals(segments[0]) ? segments[1] : null;
    }

    @Override
    public void close() {
        // The HTTP client and retry executor are owned by the factory and outlive this provider.
    }
}
