package org.openphc.cce.admin.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openphc.cce.admin.dto.PageMeta;
import org.openphc.cce.admin.dto.UserResponse;
import org.openphc.cce.admin.entity.Role;
import org.openphc.cce.admin.entity.User;
import org.openphc.cce.admin.entity.UserRole;
import org.openphc.cce.admin.entity.UserStatus;
import org.openphc.cce.admin.exception.ValidationException;
import org.openphc.cce.admin.repository.RoleRepository;
import org.openphc.cce.admin.repository.UserRepository;
import org.openphc.cce.admin.repository.UserRoleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET /users (B-30): filtered, server-side paginated, newest first. */
@Service
public class UserQueryService {

  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 100;

  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;
  private final RoleRepository roleRepository;

  public UserQueryService(UserRepository userRepository, UserRoleRepository userRoleRepository, RoleRepository roleRepository) {
    this.userRepository = userRepository;
    this.userRoleRepository = userRoleRepository;
    this.roleRepository = roleRepository;
  }

  public record Result(List<UserResponse> users, PageMeta pagination) {}

  @Transactional(readOnly = true)
  public Result list(String status, UUID roleId, String facilityScope, String q, Integer page, Integer pageSize) {
    int pageNo = page == null ? 0 : page;
    int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    if (pageNo < 0) {
      throw new ValidationException("page must be 0 or greater");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }
    UserStatus statusFilter = null;
    if (status != null && !status.isBlank()) {
      try {
        statusFilter = UserStatus.valueOf(status.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("Unknown status: " + status);
      }
    }

    Page<User> result =
        userRepository.findAll(
            specification(statusFilter, roleId, facilityScope, q),
            PageRequest.of(pageNo, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));

    List<UUID> ids = result.getContent().stream().map(User::getId).toList();
    Map<UUID, List<String>> rolesByUser = rolesFor(ids);
    List<UserResponse> users =
        result.getContent().stream().map(u -> UserResponse.from(u, rolesByUser.getOrDefault(u.getId(), List.of()))).toList();
    return new Result(users, new PageMeta(pageNo, size, result.getTotalElements()));
  }

  @Transactional(readOnly = true)
  public UserResponse get(UUID id) {
    User user = userRepository.findById(id).orElseThrow(() -> org.openphc.cce.admin.exception.NotFoundException.user(id));
    return UserResponse.from(user, rolesFor(List.of(id)).getOrDefault(id, List.of()));
  }

  private Map<UUID, List<String>> rolesFor(List<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    List<UserRole> links = userRoleRepository.findAllByUserIdIn(userIds);
    Map<UUID, String> roleNames =
        roleRepository.findAllById(links.stream().map(UserRole::getRoleId).distinct().toList()).stream()
            .collect(Collectors.toMap(Role::getId, Role::getName));
    Map<UUID, List<String>> byUser = new HashMap<>();
    for (UserRole link : links) {
      byUser.computeIfAbsent(link.getUserId(), k -> new ArrayList<>()).add(roleNames.get(link.getRoleId()));
    }
    byUser.values().forEach(names -> names.sort(String::compareTo));
    return byUser;
  }

  private static Specification<User> specification(UserStatus status, UUID roleId, String facilityScope, String q) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      }
      if (facilityScope != null && !facilityScope.isBlank()) {
        predicates.add(cb.equal(cb.lower(root.get("facilityScope")), facilityScope.trim().toLowerCase()));
      }
      if (q != null && !q.isBlank()) {
        String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("username")), like, '\\'),
                cb.like(cb.lower(root.get("email")), like, '\\'),
                cb.like(cb.lower(root.get("firstName")), like, '\\'),
                cb.like(cb.lower(root.get("lastName")), like, '\\')));
      }
      if (roleId != null) {
        Subquery<Integer> holds = query.subquery(Integer.class);
        Root<UserRole> link = holds.from(UserRole.class);
        holds.select(cb.literal(1)).where(cb.equal(link.get("userId"), root.get("id")), cb.equal(link.get("roleId"), roleId));
        predicates.add(cb.exists(holds));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
