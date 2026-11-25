package org.openphc.cce.admin.client;

/**
 * The editable Keycloak profile. Email is already lower-cased. {@code emailVerified}: false forces a
 * re-verify, true marks it verified, null leaves Keycloak's current value untouched.
 */
public record KeycloakUserProfile(String firstName, String lastName, String email, Boolean emailVerified) {}
