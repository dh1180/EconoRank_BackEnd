package com.statagora;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CommunityController {
  private static final String LANGUAGE_REGEX =
      "en|ko|ja|zh|es|fr|de|pt|ru|ar|hi|bn|id|vi|th|tr|it|nl|pl|uk|sv|no|da|fi|cs|hu|ro|el|he|fa|ms|tl|sw|ur|ta|te|mr|gu|kn|ml|pa|my|km|lo|mn|kk|uz|az|ka|hy|sk|bg|hr|sr|sl|lt|lv|et|is|ga|ca|eu|gl|af|am|ha|yo|zu|so|ne|si|ps|ku|sq|mk|bs|mt|cy";

  private static final Map<String, String> DEFAULT_GUEST_NAMES =
      Map.ofEntries(
          Map.entry("KOR", "ㅇㅇ"),
          Map.entry("JPN", "名無し"),
          Map.entry("CHN", "匿名"),
          Map.entry("TWN", "匿名"),
          Map.entry("HKG", "匿名"),
          Map.entry("RUS", "Аноним"),
          Map.entry("UKR", "Анонім"),
          Map.entry("SAU", "مجهول"),
          Map.entry("ARE", "مجهول"),
          Map.entry("EGY", "مجهول"),
          Map.entry("QAT", "مجهول"),
          Map.entry("KWT", "مجهول"),
          Map.entry("DEU", "Anonym"),
          Map.entry("AUT", "Anonym"),
          Map.entry("CHE", "Anonym"),
          Map.entry("FRA", "Anonyme"),
          Map.entry("BEL", "Anonyme"),
          Map.entry("ESP", "Anónimo"),
          Map.entry("MEX", "Anónimo"),
          Map.entry("ARG", "Anónimo"),
          Map.entry("COL", "Anónimo"),
          Map.entry("CHL", "Anónimo"),
          Map.entry("PER", "Anónimo"),
          Map.entry("BRA", "Anônimo"),
          Map.entry("PRT", "Anônimo"),
          Map.entry("ITA", "Anonimo"),
          Map.entry("TUR", "Anonim"),
          Map.entry("VNM", "Ẩn danh"),
          Map.entry("THA", "นิรนาม"),
          Map.entry("IND", "अनाम"),
          Map.entry("IDN", "Anonim"),
          Map.entry("MYS", "Anonim"));

  private final JdbcTemplate db;
  private final RequestLimits limits;
  private final GeoCountryService geo;
  private final PasswordEncoder encoder;
  private final TranslationService translation;

  CommunityController(
      JdbcTemplate db,
      RequestLimits limits,
      GeoCountryService geo,
      PasswordEncoder encoder,
      TranslationService translation) {
    this.db = db;
    this.limits = limits;
    this.geo = geo;
    this.encoder = encoder;
    this.translation = translation;
  }

  record PostInput(
      @NotBlank @Size(max = 40) String spaceId,
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 10000) String body,
      @Pattern(regexp = LANGUAGE_REGEX) @NotNull String language) {}

  record GuestPostInput(
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 10000) String body,
      @Pattern(regexp = LANGUAGE_REGEX) @NotNull String language,
      @Size(max = 40) String guestName,
      @NotBlank @Size(min = 4, max = 32) String guestPassword) {}

  record CommentInput(@NotBlank @Size(max = 3000) String body) {}

  record GuestCommentInput(
      @NotBlank @Size(max = 3000) String body,
      @Size(max = 40) String guestName,
      @NotBlank @Size(min = 4, max = 32) String guestPassword) {}

  record GuestDeleteInput(@NotBlank @Size(min = 4, max = 32) String guestPassword) {}

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
      @RequestParam(defaultValue = "latest") String sort,
      Principal principal) {
    EconomyController.pagination(page, size);
    String viewer = viewer(principal);
    String targetSpace = "global";
    String condition =
        " FROM post p LEFT JOIN member m ON m.id=p.author_id WHERE p.space_id=? AND"
            + " p.status='VISIBLE' AND (p.author_id IS NULL OR NOT EXISTS (SELECT 1 FROM"
            + " member_block b WHERE b.blocker_id=? AND b.blocked_id=p.author_id))";
    String orderBy =
        "popular".equalsIgnoreCase(sort)
            ? " ORDER BY (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id) DESC, p.created_at DESC, p.id"
            : " ORDER BY p.created_at DESC, p.id";
    var items =
        db.queryForList(
            "SELECT p.id,p.space_id AS \"spaceId\",p.title,p.body,p.language,p.author_id AS"
                + " \"authorId\",COALESCE(m.display_name,p.guest_name) AS"
                + " \"author\",COALESCE(m.country_code,p.guest_country) AS"
                + " \"authorCountry\",CASE WHEN p.author_id IS NULL THEN TRUE ELSE FALSE END AS"
                + " \"anonymous\",p.created_at AS \"createdAt\",(SELECT COUNT(*) FROM post_like l"
                + " WHERE l.post_id=p.id) AS \"likeCount\",(SELECT COUNT(*) FROM comment c WHERE"
                + " c.post_id=p.id AND c.status='VISIBLE') AS \"commentCount\",CASE WHEN"
                + " EXISTS(SELECT 1 FROM post_like l WHERE l.post_id=p.id AND l.member_id=?)"
                + " THEN TRUE ELSE FALSE END AS \"liked\""
                + condition
                + orderBy
                + " LIMIT ? OFFSET ?",
            viewer,
            targetSpace,
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
        db.queryForObject("SELECT COUNT(*)" + condition, Integer.class, targetSpace, viewer));
  }

  @GetMapping("/posts/{id}")
  Map<String, Object> post(@PathVariable String id, Principal principal) {
    var post = visiblePost(id, viewer(principal));
    var comments =
        db.queryForList(
            """
SELECT c.id,c.body,c.author_id AS "authorId",COALESCE(m.display_name,c.guest_name) AS author,
COALESCE(m.country_code,c.guest_country) AS "authorCountry",
CASE WHEN c.author_id IS NULL THEN TRUE ELSE FALSE END AS "anonymous",c.created_at AS "createdAt"
FROM comment c LEFT JOIN member m ON m.id=c.author_id WHERE c.post_id=? AND c.status='VISIBLE'
AND (c.author_id IS NULL OR NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id=? AND b.blocked_id=c.author_id))
ORDER BY c.created_at,c.id LIMIT 100
""",
            id,
            viewer(principal));
    return Map.of("post", post, "comments", comments, "commentLimit", 100);
  }

  @GetMapping("/posts/{id}/translate")
  Map<String, Object> translatePost(
      @PathVariable String id,
      @RequestParam String target,
      Principal principal,
      HttpServletRequest request) {
    if (target == null || !target.matches(LANGUAGE_REGEX)) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported target language");
    }
    limits.check("translate:" + request.getRemoteAddr(), 30, 60);
    var post = visiblePost(id, viewer(principal));
    var res =
        translation.translate(
            "post", id, (String) post.get("title"), (String) post.get("body"), target);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("title", res.title());
    out.put("body", res.body());
    out.put("targetLang", res.targetLang());
    out.put("cached", res.cached());
    out.put("provider", res.provider());
    return out;
  }

  @GetMapping("/comments/{id}/translate")
  Map<String, Object> translateComment(
      @PathVariable String id,
      @RequestParam String target,
      HttpServletRequest request) {
    if (target == null || !target.matches(LANGUAGE_REGEX)) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported target language");
    }
    limits.check("translate:" + request.getRemoteAddr(), 30, 60);
    var rows =
        db.queryForList(
            "SELECT body FROM comment WHERE id=? AND status='VISIBLE'", id);
    if (rows.isEmpty()) throw ApiErrors.missing();
    var res =
        translation.translate(
            "comment", id, null, (String) rows.get(0).get("body"), target);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("body", res.body());
    out.put("targetLang", res.targetLang());
    out.put("cached", res.cached());
    out.put("provider", res.provider());
    return out;
  }

  @PostMapping("/posts")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  Map<String, String> create(@Valid @RequestBody PostInput input, Principal principal) {
    limits.check("post:" + principal.getName(), 5, 60);
    String targetSpace = "global";
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO post(id,space_id,author_id,title,body,language) VALUES (?,?,?,?,?,?)",
        id,
        targetSpace,
        principal.getName(),
        input.title().strip(),
        input.body().strip(),
        input.language());
    event(targetSpace, "POST_CREATED", id);
    return Map.of("id", id);
  }

  @PostMapping("/guest/posts")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  Map<String, String> createGuestPost(
      @Valid @RequestBody GuestPostInput input, HttpServletRequest request) {
    limits.check("guest-post:" + request.getRemoteAddr(), 5, 60);
    String countryCode = geo.detectCountryIso3(request);
    String guestName = resolveGuestName(input.guestName(), countryCode);
    String postLang =
        countryCode != null
            ? GeoCountryService.countryToLanguage(countryCode)
            : input.language();
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " post(id,space_id,author_id,title,body,language,guest_name,guest_country,guest_password_hash)"
            + " VALUES (?, 'global', NULL, ?, ?, ?, ?, ?, ?)",
        id,
        input.title().strip(),
        input.body().strip(),
        postLang,
        guestName,
        countryCode,
        encoder.encode(input.guestPassword()));
    event("global", "POST_CREATED", id);
    return Map.of("id", id);
  }

  @PostMapping("/guest/posts/{id}/comments")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  Map<String, String> createGuestComment(
      @PathVariable String id,
      @Valid @RequestBody GuestCommentInput input,
      HttpServletRequest request) {
    limits.check("guest-comment:" + request.getRemoteAddr(), 10, 60);
    var post = visiblePost(id, "");
    String countryCode = geo.detectCountryIso3(request);
    String guestName = resolveGuestName(input.guestName(), countryCode);
    String commentId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " comment(id,post_id,author_id,body,guest_name,guest_country,guest_password_hash)"
            + " VALUES (?, ?, NULL, ?, ?, ?, ?)",
        commentId,
        id,
        input.body().strip(),
        guestName,
        countryCode,
        encoder.encode(input.guestPassword()));
    event((String) post.get("spaceId"), "COMMENT_CREATED", commentId);
    return Map.of("id", commentId);
  }

  @PostMapping("/guest/posts/{id}/delete")
  @Transactional
  Map<String, Boolean> deleteGuestPost(
      @PathVariable String id,
      @Valid @RequestBody GuestDeleteInput input,
      HttpServletRequest request) {
    limits.check("guest-del:" + request.getRemoteAddr(), 15, 60);
    return deleteGuest("post", id, input.guestPassword());
  }

  @PostMapping("/guest/comments/{id}/delete")
  @Transactional
  Map<String, Boolean> deleteGuestComment(
      @PathVariable String id,
      @Valid @RequestBody GuestDeleteInput input,
      HttpServletRequest request) {
    limits.check("guest-del:" + request.getRemoteAddr(), 15, 60);
    return deleteGuest("comment", id, input.guestPassword());
  }

  private Map<String, Boolean> deleteGuest(String table, String id, String password) {
    var rows =
        db.queryForList(
            "SELECT guest_password_hash FROM "
                + table
                + " WHERE id=? AND author_id IS NULL AND status='VISIBLE'",
            id);
    if (rows.isEmpty()) throw ApiErrors.missing();
    String hash = (String) rows.get(0).get("guest_password_hash");
    if (hash == null || !encoder.matches(password, hash)) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.FORBIDDEN, "Invalid guest password");
    }
    db.update("UPDATE " + table + " SET status='DELETED' WHERE id=?", id);
    return Map.of("ok", true);
  }

  private static String resolveGuestName(String provided, String countryCode) {
    if (provided != null && !provided.isBlank()) return provided.strip();
    if (countryCode != null) {
      return DEFAULT_GUEST_NAMES.getOrDefault(countryCode.toUpperCase(Locale.ROOT), "Anon");
    }
    return "Anon";
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

  @PostMapping("/posts/{id}/like")
  @Transactional
  Map<String, Object> toggleLike(@PathVariable String id, Principal principal) {
    if (principal == null) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    }
    String memberId = principal.getName();
    visiblePost(id, memberId);
    limits.check("like:" + memberId, 60, 60);
    int deleted =
        db.update("DELETE FROM post_like WHERE post_id=? AND member_id=?", id, memberId);
    boolean liked;
    if (deleted > 0) {
      liked = false;
    } else {
      db.update("INSERT INTO post_like(post_id,member_id) VALUES (?,?)", id, memberId);
      liked = true;
    }
    int count =
        db.queryForObject(
            "SELECT COUNT(*) FROM post_like WHERE post_id=?", Integer.class, id);
    return Map.of("liked", liked, "likeCount", count);
  }

  @GetMapping("/me/posts")
  List<Map<String, Object>> myPosts(Principal principal) {
    if (principal == null) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    }
    String memberId = principal.getName();
    return db.queryForList(
        """
        SELECT p.id,p.title,p.body,p.language,p.created_at AS "createdAt",
        (SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id) AS "likeCount",
        (SELECT COUNT(*) FROM comment c WHERE c.post_id=p.id AND c.status='VISIBLE') AS "commentCount"
        FROM post p WHERE p.author_id=? AND p.status='VISIBLE'
        ORDER BY p.created_at DESC LIMIT 100
        """,
        memberId);
  }

  @GetMapping("/me/comments")
  List<Map<String, Object>> myComments(Principal principal) {
    if (principal == null) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    }
    String memberId = principal.getName();
    return db.queryForList(
        """
        SELECT c.id,c.post_id AS "postId",p.title AS "postTitle",c.body,c.created_at AS "createdAt"
        FROM comment c JOIN post p ON p.id=c.post_id
        WHERE c.author_id=? AND c.status='VISIBLE' AND p.status='VISIBLE'
        ORDER BY c.created_at DESC LIMIT 100
        """,
        memberId);
  }

  @GetMapping("/me/likes")
  List<Map<String, Object>> myLikes(Principal principal) {
    if (principal == null) {
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    }
    String memberId = principal.getName();
    return db.queryForList(
        """
        SELECT p.id,p.title,p.body,p.language,
        COALESCE(m.display_name,p.guest_name) AS author,
        COALESCE(m.country_code,p.guest_country) AS "authorCountry",
        p.created_at AS "createdAt",
        (SELECT COUNT(*) FROM post_like l2 WHERE l2.post_id=p.id) AS "likeCount",
        l.created_at AS "likedAt"
        FROM post_like l
        JOIN post p ON p.id=l.post_id
        LEFT JOIN member m ON m.id=p.author_id
        WHERE l.member_id=? AND p.status='VISIBLE'
        ORDER BY l.created_at DESC LIMIT 100
        """,
        memberId);
  }

  private Map<String, Object> visiblePost(String id, String viewer) {
    var rows =
        db.queryForList(
            """
SELECT p.id,p.title,p.body,p.language,p.space_id AS "spaceId",p.author_id AS "authorId",
COALESCE(m.display_name,p.guest_name) AS author,COALESCE(m.country_code,p.guest_country) AS "authorCountry",
CASE WHEN p.author_id IS NULL THEN TRUE ELSE FALSE END AS "anonymous",p.created_at AS "createdAt",
(SELECT COUNT(*) FROM post_like l WHERE l.post_id=p.id) AS "likeCount",
CASE WHEN EXISTS(SELECT 1 FROM post_like l WHERE l.post_id=p.id AND l.member_id=?) THEN TRUE ELSE FALSE END AS "liked"
FROM post p LEFT JOIN member m ON m.id=p.author_id WHERE p.id=? AND p.status='VISIBLE'
AND (p.author_id IS NULL OR NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id=? AND b.blocked_id=p.author_id))
""",
            viewer,
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
