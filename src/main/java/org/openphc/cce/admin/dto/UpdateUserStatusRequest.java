package org.openphc.cce.admin.dto;

import jakarta.validation.constraints.NotNull;
import org.openphc.cce.admin.entity.UserStatus;

public record UpdateUserStatusRequest(@NotNull UserStatus status) {}
