package com.statagora;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CommunityController {
  private final JdbcTemplate db;
  private final RequestLimits limits;

  CommunityController(JdbcTemplate db, RequestLimits limits) {
    this.db = db;
    this.limits = limits;
  }

  record PostInput(
      @NotBlank @Size(max = 40) String spaceId,
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 10000) String body,
      @Pattern(regexp = "en|ko") @NotNull String language) {}

  record CommentInput(@NotBlank @Size(max = 3000) String body) {}

  record ReportInput(
      @Pattern(regexp = "post|comment") @NotNull String targetType,
      @NotBlank @Size(max = 36) String targetId,
      @NotBlank @Size(max = 500) String reason) {}

  record ModerationInput(
      @Pattern(regexp = "post|comment") @NotNull String targetType,
      @NotBlank @Size(max = 36) String targetId) {}

  @GetMapping("/posts")
  Map<String, Object> posts(
      @RequestParam(defaultValue = "global") String space,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Principal principal) {
    EconomyController.pagination(page, size);
    String viewer = viewer(principal);
    space(space);
    String condition =
        " FROM post p JOIN member m ON m.id=p.author_id WHERE p.space_id=? AND p.status='VISIBLE'"
            + " AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id=? AND"
            + " b.blocked_id=p.author_id)";
    var items =
        db.queryForList(
            "SELECT p.id,p.space_id AS \"spaceId\",p.title,p.body,p.language,p.author_id AS"
                + " \"authorId\",m.display_name AS \"author\",p.created_at AS \"createdAt\""
                + condition
                + " ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",
            space,
            viewer,
            size,
            page * size);
    return Map.of(
        "items",
        items,
        "page",
        page,
        "size",
        size,
        "total",
        db.queryForObject("SELECT COUNT(*)" + condition, Integer.class, space, viewer));
  }

  @GetMapping("/posts/{id}")
  Map<String, Object> post(@PathVariable String id, Principal principal) {
    var post = visiblePost(id, viewer(principal));
    var comments =
        db.queryForList(
            """
SELECT c.id,c.body,c.author_id AS "authorId",m.display_name AS author,c.created_at AS "createdAt"
FROM comment c JOIN member m ON m.id=c.author_id WHERE c.post_id=? AND c.status='VISIBLE'
AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id=? AND b.blocked_id=c.author_id)
ORDER BY c.created_at,c.id LIMIT 100
""",
            id,
            viewer(principal));
    return Map.of("post", post, "comments", comments, "commentLimit", 100);
  }

  @PostMapping("/posts")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  Map<String, String> create(@Valid @RequestBody PostInput input, Principal principal) {
    limits.check("post:" + principal.getName(), 5, 60);
    space(input.spaceId());
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO post(id,space_id,author_id,title,body,language) VALUES (?,?,?,?,?,?)",
        id,
        input.spaceId(),
        principal.getName(),
        input.title().strip(),
        input.body().strip(),
        input.language());
    event(input.spaceId(), "POST_CREATED", id);
    return Map.of("id", id);
  }

  @PostMapping("/posts/{id}/comments")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  Map<String, String> comment(
      @PathVariable String id, @Valid @RequestBody CommentInput input, Principal principal) {
    limits.check("comment:" + principal.getName(), 10, 60);
    var post = visiblePost(id, principal.getName());
    String comment = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO comment(id,post_id,author_id,body) VALUES (?,?,?,?)",
        comment,
        id,
        principal.getName(),
        input.body().strip());
    event((String) post.get("spaceId"), "COMMENT_CREATED", comment);
    return Map.of("id", comment);
  }

  @DeleteMapping("/posts/{id}")
  @Transactional
  Map<String, Boolean> deletePost(@PathVariable String id, Principal principal) {
    return delete("post", id, principal);
  }

  @DeleteMapping("/comments/{id}")
  @Transactional
  Map<String, Boolean> deleteComment(@PathVariable String id, Principal principal) {
    return delete("comment", id, principal);
  }

  private Map<String, Boolean> delete(String table, String id, Principal principal) {
    if (db.update(
            "UPDATE "
                + table
                + " SET status='DELETED' WHERE id=? AND author_id=? AND status='VISIBLE'",
            id,
            principal.getName())
        == 0) throw ApiErrors.missing();
    return Map.of("ok", true);
  }

  @PostMapping("/reports")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  Map<String, Boolean> report(@Valid @RequestBody ReportInput input, Principal principal) {
    limits.check("report:" + principal.getName(), 10, 60);
    target(input.targetType(), input.targetId(), principal.getName());
    db.update(
        "INSERT INTO report(id,reporter_id,target_type,target_id,reason) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        principal.getName(),
        input.targetType(),
        input.targetId(),
        input.reason().strip());
    return Map.of("ok", true);
  }

  @PostMapping("/blocks/{memberId}")
  Map<String, Boolean> block(@PathVariable String memberId, Principal principal) {
    if (memberId.equals(principal.getName())) throw ApiErrors.bad("Cannot block yourself");
    if (db.queryForObject("SELECT COUNT(*) FROM member WHERE id=?", Integer.class, memberId) == 0)
      throw ApiErrors.missing();
    db.update("INSERT INTO member_block VALUES (?,?)", principal.getName(), memberId);
    return Map.of("ok", true);
  }

  @GetMapping("/blocks")
  List<Map<String, Object>> blocks(Principal principal) {
    if (principal == null)
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    return db.queryForList(
        "SELECT m.id,m.display_name AS \"displayName\" FROM member_block b JOIN member m ON"
            + " m.id=b.blocked_id WHERE b.blocker_id=?",
        principal.getName());
  }

  @DeleteMapping("/blocks/{memberId}")
  Map<String, Boolean> unblock(@PathVariable String memberId, Principal principal) {
    db.update(
        "DELETE FROM member_block WHERE blocker_id=? AND blocked_id=?",
        principal.getName(),
        memberId);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/reports")
  List<Map<String, Object>> reports(@RequestParam(defaultValue = "0") int page) {
    EconomyController.pagination(page, 50);
    return db.queryForList(
        """
SELECT r.id,r.target_type AS "targetType",r.target_id AS "targetId",r.reason,r.status,r.created_at AS "createdAt",
COALESCE(p.body,c.body) AS "content",COALESCE(p.id,c.post_id) AS "postId"
FROM report r LEFT JOIN post p ON r.target_type='post' AND p.id=r.target_id
LEFT JOIN comment c ON r.target_type='comment' AND c.id=r.target_id
ORDER BY r.created_at DESC LIMIT 50 OFFSET ?
""",
        page * 50);
  }

  @PostMapping("/admin/hide")
  @Transactional
  Map<String, Boolean> hide(@Valid @RequestBody ModerationInput input, Principal principal) {
    String table = input.targetType();
    if (db.update(
            "UPDATE " + table + " SET status='HIDDEN' WHERE id=? AND status='VISIBLE'",
            input.targetId())
        == 0) throw ApiErrors.missing();
    db.update(
        "INSERT INTO moderation_event(id,moderator_id,target_type,target_id,action) VALUES"
            + " (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        principal.getName(),
        table,
        input.targetId(),
        "HIDE");
    db.update(
        "UPDATE report SET status='RESOLVED' WHERE target_type=? AND target_id=?",
        table,
        input.targetId());
    return Map.of("ok", true);
  }

  private void target(String type, String id, String viewer) {
    if (type.equals("post")) visiblePost(id, viewer);
    else {
      var rows =
          db.queryForList(
              "SELECT post_id,author_id FROM comment WHERE id=? AND status='VISIBLE'", id);
      if (rows.isEmpty()) throw ApiErrors.missing();
      visiblePost((String) rows.get(0).get("post_id"), viewer);
      if (db.queryForObject(
              "SELECT COUNT(*) FROM member_block WHERE blocker_id=? AND blocked_id=?",
              Integer.class,
              viewer,
              rows.get(0).get("author_id"))
          > 0) throw ApiErrors.missing();
    }
  }

  private Map<String, Object> visiblePost(String id, String viewer) {
    var rows =
        db.queryForList(
            """
SELECT p.id,p.title,p.body,p.language,p.space_id AS "spaceId",p.author_id AS "authorId",m.display_name AS author,p.created_at AS "createdAt"
FROM post p JOIN member m ON m.id=p.author_id WHERE p.id=? AND p.status='VISIBLE'
AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id=? AND b.blocked_id=p.author_id)
""",
            id,
            viewer);
    if (rows.isEmpty()) throw ApiErrors.missing();
    return rows.get(0);
  }

  private void space(String id) {
    if (db.queryForObject("SELECT COUNT(*) FROM discussion_space WHERE id=?", Integer.class, id)
        == 0) throw ApiErrors.missing();
  }

  private String viewer(Principal principal) {
    return principal == null ? "" : principal.getName();
  }

  private void event(String space, String type, String id) {
    db.update(
        "INSERT INTO community_event(id,space_id,event_type,aggregate_id) VALUES (?,?,?,?)",
        UUID.randomUUID().toString(),
        space,
        type,
        id);
  }
}
