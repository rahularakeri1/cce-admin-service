package org.openphc.cce.admin.client;

/** What to create in Keycloak. Username and email are already lower-cased (Keycloak lower-cases them). */
public record KeycloakUserSpec(String username, String email, String firstName, String lastName) {}
