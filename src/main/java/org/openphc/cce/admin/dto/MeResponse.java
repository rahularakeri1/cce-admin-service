package org.openphc.cce.admin.dto;

import java.util.List;
import java.util.UUID;

/** GET /me payload — roles are local role names, not role objects (docs/api-reference.md). */
public record MeResponse(UUID id, String username, List<String> roles, String facilityScope) {}
