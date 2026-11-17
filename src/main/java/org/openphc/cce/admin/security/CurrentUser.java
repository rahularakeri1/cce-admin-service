package org.openphc.cce.admin.security;

import java.util.List;
import java.util.UUID;

/** Resolved once per request by CurrentUserAuthenticationFilter — see docs/architecture-overview.md §5. */
public record CurrentUser(UUID id, String username, List<String> roles, String facilityScope) {}
