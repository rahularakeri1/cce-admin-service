package org.openphc.cce.admin.client;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.openphc.cce.admin.config.KeycloakProperties;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.springframework.stereotype.Component;

/** keycloak-admin-client behind the {@link KeycloakGateway} interface. Built lazily on first use. */
@Component
public class AdminClientKeycloakGateway implements KeycloakGateway {

  private final KeycloakProperties props;
  private volatile Keycloak client;

  public AdminClientKeycloakGateway(KeycloakProperties props) {
    this.props = props;
  }

  private RealmResource realm() {
    Keycloak k = client;
    if (k == null) {
      synchronized (this) {
        if (client == null) {
          client = build();
        }
        k = client;
      }
    }
    return k.realm(props.realm());
  }

  private Keycloak build() {
    // Explicit timeouts: a hung Keycloak must become a 502, not a hung request holding a DB
    // transaction open (user creation keeps one open across the Keycloak calls).
    var http =
        new ResteasyClientBuilderImpl()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPoolSize(10)
            .build();
    KeycloakBuilder builder = KeycloakBuilder.builder().serverUrl(props.serverUrl()).resteasyClient(http);
    if (props.useClientCredentials()) {
      return builder
          .realm(props.realm())
          .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
          .clientId(props.adminClientId())
          .clientSecret(props.adminClientSecret())
          .build();
    }
    return builder
        .realm(props.adminRealm())
        .grantType(OAuth2Constants.PASSWORD)
        .clientId(props.adminClientId())
        .username(props.adminUsername())
        .password(props.adminPassword())
        .build();
  }

  @Override
  public List<String> missingRealmRoles(Collection<String> roleNames) {
    List<String> missing = new ArrayList<>();
    try {
      for (String name : roleNames) {
        try {
          realm().roles().get(name).toRepresentation();
        } catch (NotFoundException e) {
          missing.add(name);
        }
      }
    } catch (RuntimeException e) {
      throw failure("checking realm roles", e);
    }
    return missing;
  }

  @Override
  public String createUser(KeycloakUserSpec spec) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(spec.username());
    user.setEmail(spec.email());
    user.setFirstName(spec.firstName());
    user.setLastName(spec.lastName());
    user.setEnabled(true);
    user.setEmailVerified(false);

    try (Response response = realm().users().create(user)) {
      if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
        String body = response.hasEntity() ? response.readEntity(String.class) : "";
        KeycloakUserExistsException.Field field =
            body.toLowerCase().contains("username")
                ? KeycloakUserExistsException.Field.USERNAME
                : KeycloakUserExistsException.Field.EMAIL;
        throw new KeycloakUserExistsException(field, "Keycloak already has that " + field.name().toLowerCase());
      }
      return CreatedResponseUtil.getCreatedId(response);
    } catch (KeycloakUserExistsException e) {
      throw e;
    } catch (WebApplicationException e) {
      throw new SyncFailedException(
          "Keycloak rejected user creation (HTTP " + e.getResponse().getStatus() + ")", e);
    } catch (RuntimeException e) {
      throw failure("creating the user", e);
    }
  }

  @Override
  public void assignRealmRoles(String keycloakUserId, Collection<String> roleNames) {
    if (roleNames.isEmpty()) {
      return;
    }
    try {
      RealmResource realm = realm();
      List<RoleRepresentation> roles = new ArrayList<>();
      for (String name : roleNames) {
        roles.add(realm.roles().get(name).toRepresentation());
      }
      realm.users().get(keycloakUserId).roles().realmLevel().add(roles);
    } catch (RuntimeException e) {
      throw failure("assigning realm roles", e);
    }
  }

  @Override
  public String findUserId(String username) {
    try {
      List<UserRepresentation> found = realm().users().searchByUsername(username, true);
      if (found.isEmpty()) {
        throw new SyncFailedException("User '" + username + "' does not exist in Keycloak");
      }
      return found.get(0).getId();
    } catch (SyncFailedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure("looking the user up", e);
    }
  }

  @Override
  public KeycloakUserProfile updateUser(String keycloakUserId, KeycloakUserProfile profile) {
    try {
      var resource = realm().users().get(keycloakUserId);
      UserRepresentation user = resource.toRepresentation();
      KeycloakUserProfile before =
          new KeycloakUserProfile(user.getFirstName(), user.getLastName(), user.getEmail(), user.isEmailVerified());
      user.setFirstName(profile.firstName());
      user.setLastName(profile.lastName());
      user.setEmail(profile.email());
      if (profile.emailVerified() != null) {
        user.setEmailVerified(profile.emailVerified());
      }
      resource.update(user);
      return before;
    } catch (RuntimeException e) {
      throw failure("updating the user", e);
    }
  }

  @Override
  public void setEnabled(String keycloakUserId, boolean enabled) {
    try {
      var resource = realm().users().get(keycloakUserId);
      UserRepresentation user = resource.toRepresentation();
      user.setEnabled(enabled);
      resource.update(user);
    } catch (RuntimeException e) {
      throw failure(enabled ? "enabling the user" : "disabling the user", e);
    }
  }

  @Override
  public void removeRealmRoles(String keycloakUserId, Collection<String> roleNames) {
    if (roleNames.isEmpty()) {
      return;
    }
    try {
      RealmResource realm = realm();
      List<RoleRepresentation> roles = new ArrayList<>();
      for (String name : roleNames) {
        roles.add(realm.roles().get(name).toRepresentation());
      }
      realm.users().get(keycloakUserId).roles().realmLevel().remove(roles);
    } catch (RuntimeException e) {
      throw failure("removing realm roles", e);
    }
  }

  @Override
  public void logoutUser(String keycloakUserId) {
    try {
      realm().users().get(keycloakUserId).logout();
    } catch (RuntimeException e) {
      throw failure("ending the user's sessions", e);
    }
  }

  @Override
  public void sendActionsEmail(String keycloakUserId, List<String> actions, long lifespanSeconds) {
    try {
      var user = realm().users().get(keycloakUserId);
      int lifespan = (int) lifespanSeconds;
      if (props.inviteClientId() != null && !props.inviteClientId().isBlank()) {
        user.executeActionsEmail(props.inviteClientId(), props.inviteRedirectUri(), lifespan, actions);
      } else {
        user.executeActionsEmail(actions, lifespan);
      }
    } catch (RuntimeException e) {
      throw failure("sending the invitation email", e);
    }
  }

  private static SyncFailedException failure(String what, RuntimeException e) {
    String detail =
        e instanceof ProcessingException
            ? "Keycloak unreachable or timed out"
            : e instanceof WebApplicationException w
                ? "HTTP " + w.getResponse().getStatus()
                : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    return new SyncFailedException("Keycloak failed while " + what + ": " + detail, e);
  }
}
