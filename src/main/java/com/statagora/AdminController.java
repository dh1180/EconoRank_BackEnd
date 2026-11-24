package com.statagora;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
class AdminController {
  private final JdbcTemplate db;
  private final AdminRegistry admins;
  private final TranslationService translation;
  private final CompanyMarketCapService companies;

  AdminController(
      JdbcTemplate db,
      AdminRegistry admins,
      TranslationService translation,
      CompanyMarketCapService companies) {
    this.db = db;
    this.admins = admins;
    this.translation = translation;
    this.companies = companies;
  }

  private long count(String sql, Object... args) {
    Long v = db.queryForObject(sql, Long.class, args);
    return v == null ? 0L : v;
  }

  private void recordModeration(String moderatorId, String targetType, String targetId, String action) {
    db.update(
        "INSERT INTO moderation_event(id, moderator_id, target_type, target_id, action)"
            + " VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        moderatorId,
        targetType,
        targetId,
        action);
  }

  @GetMapping("/overview")
  Map<String, Object> overview() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("membersTotal", count("SELECT COUNT(*) FROM member"));
    out.put("adminsTotal", count("SELECT COUNT(*) FROM member WHERE role='ADMIN'"));
    out.put("postsVisible", count("SELECT COUNT(*) FROM post WHERE status='VISIBLE'"));
    out.put("postsHidden", count("SELECT COUNT(*) FROM post WHERE status<>'VISIBLE'"));
    out.put("commentsVisible", count("SELECT COUNT(*) FROM comment WHERE status='VISIBLE'"));
    out.put("commentsHidden", count("SELECT COUNT(*) FROM comment WHERE status<>'VISIBLE'"));
    out.put("likesTotal", count("SELECT COUNT(*) FROM post_like"));
    out.put("reportsOpen", count("SELECT COUNT(*) FROM report WHERE status='OPEN'"));
    out.put("translationsCached", count("SELECT COUNT(*) FROM content_translation"));
    out.put("translationProvider", translation.resolveProvider());

    CompanyMarketCapService.Snapshot snap = companies.current();
    out.put("companiesCount", snap == null ? 0 : snap.items().size());
    out.put("companiesUpdatedAt", snap == null ? null : snap.updatedAt().toString());
    return out;
  }

  @GetMapping("/members")
  Map<String, Object> members(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "50") int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 200));
    String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    List<Map<String, Object>> items;
    if (query.isEmpty()) {
      items =
          db.queryForList(
              "SELECT m.id, m.email, m.display_name AS \"displayName\", m.role,"
                  + " m.country_code AS \"countryCode\", m.created_at AS \"createdAt\","
                  + " (SELECT COUNT(*) FROM post p WHERE p.author_id=m.id) AS \"postCount\","
                  + " (SELECT COUNT(*) FROM comment c WHERE c.author_id=m.id) AS \"commentCount\""
                  + " FROM member m ORDER BY m.created_at DESC LIMIT ?",
              safeLimit);
    } else {
      String pattern = "%" + query + "%";
      items =
          db.queryForList(
              "SELECT m.id, m.email, m.display_name AS \"displayName\", m.role,"
                  + " m.country_code AS \"countryCode\", m.created_at AS \"createdAt\","
                  + " (SELECT COUNT(*) FROM post p WHERE p.author_id=m.id) AS \"postCount\","
                  + " (SELECT COUNT(*) FROM comment c WHERE c.author_id=m.id) AS \"commentCount\""
                  + " FROM member m"
                  + " WHERE LOWER(m.email) LIKE ? OR LOWER(m.display_name) LIKE ?"
                  + " ORDER BY m.created_at DESC LIMIT ?",
              pattern,
              pattern,
              safeLimit);
    }
    return Map.of("items", items, "total", items.size());
  }

  record RoleUpdate(@NotBlank String role) {}

  @PatchMapping("/members/{id}/role")
  Map<String, Object> updateMemberRole(
      @PathVariable String id, @Valid @RequestBody RoleUpdate body, Principal principal) {
    String nextRole = body.role().trim().toUpperCase(Locale.ROOT);
    if (!nextRole.equals("USER") && !nextRole.equals("ADMIN")) {
      throw ApiErrors.bad("Role must be USER or ADMIN");
    }
    var rows = db.queryForList("SELECT id, email, role FROM member WHERE id=?", id);
    if (rows.isEmpty()) throw ApiErrors.missing();
    String email = Objects.toString(rows.get(0).get("email"), "");
    if ("USER".equals(nextRole) && admins.isConfiguredAdmin(email)) {
      throw ApiErrors.bad("Cannot demote a configured root administrator");
    }
    if ("USER".equals(nextRole) && id.equals(principal.getName())) {
      throw ApiErrors.bad("Cannot demote your own account");
    }
    db.update("UPDATE member SET role=? WHERE id=?", nextRole, id);
    recordModeration(principal.getName(), "MEMBER", id, "ROLE_" + nextRole);
    return Map.of("id", id, "role", nextRole);
  }

  @GetMapping("/posts")
  Map<String, Object> posts(
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "50") int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 200));
    String st = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
    String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

    StringBuilder sql =
        new StringBuilder(
            "SELECT p.id, p.title, p.body, p.language, p.status, p.created_at AS \"createdAt\","
                + " COALESCE(m.display_name, p.guest_name, 'Anonymous') AS \"author\","
                + " COALESCE(m.country_code, p.guest_country) AS \"countryCode\","
                + " (p.author_id IS NULL) AS \"anonymous\","
                + " (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id) AS \"likeCount\","
                + " (SELECT COUNT(*) FROM comment c WHERE c.post_id=p.id AND c.status='VISIBLE')"
                + " AS \"commentCount\""
                + " FROM post p LEFT JOIN member m ON m.id=p.author_id WHERE 1=1");
    List<Object> params = new ArrayList<>();
    if ("VISIBLE".equals(st) || "HIDDEN".equals(st)) {
      sql.append(" AND p.status=?");
      params.add(st);
    }
    if (!query.isEmpty()) {
      String pattern = "%" + query + "%";
      sql.append(
          " AND (LOWER(p.title) LIKE ? OR LOWER(p.body) LIKE ?"
              + " OR LOWER(COALESCE(m.display_name, p.guest_name, '')) LIKE ?)");
      params.add(pattern);
      params.add(pattern);
      params.add(pattern);
    }
    sql.append(" ORDER BY p.created_at DESC LIMIT ?");
    params.add(safeLimit);

    var items = db.queryForList(sql.toString(), params.toArray());
    return Map.of("items", items, "total", items.size());
  }

  record StatusUpdate(@NotBlank String status) {}

  @PatchMapping("/posts/{id}/status")
  Map<String, Object> updatePostStatus(
      @PathVariable String id, @Valid @RequestBody StatusUpdate body, Principal principal) {
    String nextStatus = body.status().trim().toUpperCase(Locale.ROOT);
    if (!nextStatus.equals("VISIBLE") && !nextStatus.equals("HIDDEN")) {
      throw ApiErrors.bad("Status must be VISIBLE or HIDDEN");
    }
    int updated = db.update("UPDATE post SET status=? WHERE id=?", nextStatus, id);
    if (updated == 0) throw ApiErrors.missing();
    recordModeration(principal.getName(), "POST", id, nextStatus);
    return Map.of("id", id, "status", nextStatus);
  }

  @DeleteMapping("/posts/{id}")
  Map<String, Object> deletePost(@PathVariable String id, Principal principal) {
    if (count("SELECT COUNT(*) FROM post WHERE id=?", id) == 0) {
      throw ApiErrors.missing();
    }
    db.update("DELETE FROM content_translation WHERE target_type='post' AND target_id=?", id);
    db.update(
        "DELETE FROM content_translation WHERE target_type='comment'"
            + " AND target_id IN (SELECT id FROM comment WHERE post_id=?)",
        id);
    db.update("DELETE FROM post_like WHERE post_id=?", id);
    db.update("DELETE FROM comment WHERE post_id=?", id);
    db.update("DELETE FROM post WHERE id=?", id);
    recordModeration(principal.getName(), "POST", id, "DELETE");
    return Map.of("ok", true, "id", id);
  }

  @GetMapping("/comments")
  Map<String, Object> comments(
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "50") int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 200));
    String st = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
    StringBuilder sql =
        new StringBuilder(
            "SELECT c.id, c.post_id AS \"postId\", p.title AS \"postTitle\", c.body, c.status,"
                + " c.created_at AS \"createdAt\","
                + " COALESCE(m.display_name, c.guest_name, 'Anonymous') AS \"author\","
                + " COALESCE(m.country_code, c.guest_country) AS \"countryCode\","
                + " (c.author_id IS NULL) AS \"anonymous\""
                + " FROM comment c"
                + " JOIN post p ON p.id=c.post_id"
                + " LEFT JOIN member m ON m.id=c.author_id WHERE 1=1");
    List<Object> params = new ArrayList<>();
    if ("VISIBLE".equals(st) || "HIDDEN".equals(st)) {
      sql.append(" AND c.status=?");
      params.add(st);
    }
    sql.append(" ORDER BY c.created_at DESC LIMIT ?");
    params.add(safeLimit);
    var items = db.queryForList(sql.toString(), params.toArray());
    return Map.of("items", items, "total", items.size());
  }

  @PatchMapping("/comments/{id}/status")
  Map<String, Object> updateCommentStatus(
      @PathVariable String id, @Valid @RequestBody StatusUpdate body, Principal principal) {
    String nextStatus = body.status().trim().toUpperCase(Locale.ROOT);
    if (!nextStatus.equals("VISIBLE") && !nextStatus.equals("HIDDEN")) {
      throw ApiErrors.bad("Status must be VISIBLE or HIDDEN");
    }
    int updated = db.update("UPDATE comment SET status=? WHERE id=?", nextStatus, id);
    if (updated == 0) throw ApiErrors.missing();
    recordModeration(principal.getName(), "COMMENT", id, nextStatus);
    return Map.of("id", id, "status", nextStatus);
  }

  @PatchMapping("/reports/{id}")
  Map<String, Object> updateReport(
      @PathVariable String id, @Valid @RequestBody StatusUpdate body, Principal principal) {
    String nextStatus = body.status().trim().toUpperCase(Locale.ROOT);
    if (!nextStatus.equals("OPEN")
        && !nextStatus.equals("RESOLVED")
        && !nextStatus.equals("DISMISSED")) {
      throw ApiErrors.bad("Status must be OPEN, RESOLVED, or DISMISSED");
    }
    int updated = db.update("UPDATE report SET status=? WHERE id=?", nextStatus, id);
    if (updated == 0) throw ApiErrors.missing();
    recordModeration(principal.getName(), "REPORT", id, nextStatus);
    return Map.of("id", id, "status", nextStatus);
  }

  @PostMapping("/refresh/companies")
  Map<String, Object> refreshCompanies(Principal principal) {
    try {
      var snap = companies.refreshNow();
      recordModeration(principal.getName(), "SYSTEM", "companies", "REFRESH");
      return Map.of(
          "ok", true,
          "count", snap.items().size(),
          "updatedAt", snap.updatedAt().toString());
    } catch (Exception e) {
      throw ApiErrors.bad("Company refresh failed: " + e.getMessage());
    }
  }

  @DeleteMapping("/translations/cache")
  Map<String, Object> clearTranslationCache(Principal principal) {
    int deleted = db.update("DELETE FROM content_translation");
    recordModeration(principal.getName(), "SYSTEM", "translations", "CLEAR_CACHE");
    return Map.of("ok", true, "deleted", deleted);
  }
}
