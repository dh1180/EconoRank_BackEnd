package com.statagora;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository csrf;
  private final RequestLimits limits;
  private final GeoCountryService geo;
  private final AdminRegistry admins;
  private final String dummyHash;

  AuthController(
      JdbcTemplate db,
      PasswordEncoder encoder,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf,
      RequestLimits limits,
      GeoCountryService geo,
      AdminRegistry admins) {
    this.db = db;
    this.encoder = encoder;
    this.contexts = contexts;
    this.csrf = csrf;
    this.limits = limits;
    this.geo = geo;
    this.admins = admins;
    dummyHash = encoder.encode("timing-equalization-only");
  }

  record Registration(
      @Email @NotBlank @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 64) String password,
      @NotBlank @Size(max = 40) String displayName) {}

  record Login(
      @Email @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {}

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/register")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  Map<String, Object> register(
      @Valid @RequestBody Registration body,
      HttpServletRequest request,
      HttpServletResponse response) {
    limits.check("register:" + request.getRemoteAddr(), 5, 3600);
    if (body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiErrors.bad("Password must be at most 72 UTF-8 bytes");
    String id = UUID.randomUUID().toString();
    String countryCode = geo.detectCountryIso3(request);
    // Administrator promotion is an explicit database operation, never granted through public
    // registration.
    db.update(
        "INSERT INTO member(id,email,display_name,password_hash,country_code) VALUES (?,?,?,?,?)",
        id,
        body.email().toLowerCase(Locale.ROOT),
        body.displayName().strip(),
        encoder.encode(body.password()),
        countryCode);
    signIn(id, "USER", request, response);
    return member(id);
  }

  @PostMapping("/login")
  Map<String, Object> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    limits.check("login:" + request.getRemoteAddr(), 10, 60);
    var rows =
        db.queryForList(
            "SELECT * FROM member WHERE email=?", body.email().toLowerCase(Locale.ROOT));
    String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
    if (!encoder.matches(body.password(), hash) || rows.isEmpty())
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid email or password");
    var row = rows.get(0);
    String id = (String) row.get("id");
    String email = (String) row.get("email");
    String role = (String) row.get("role");
    if (admins.isConfiguredAdmin(email) && !"ADMIN".equals(role)) {
      db.update("UPDATE member SET role='ADMIN' WHERE id=?", id);
      role = "ADMIN";
    }
    signIn(id, role, request, response);
    return member(id);
  }

  void signIn(
      String id, String role, HttpServletRequest request, HttpServletResponse response) {
    request.getSession(true);
    request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            id, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    csrf.saveToken(null, request, response);
  }

  Map<String, Object> member(String id) {
    return db.queryForMap(
        "SELECT id, display_name AS \"displayName\", role, country_code AS \"countryCode\" FROM"
            + " member WHERE id=?",
        id);
  }

  record UpdateProfile(@NotBlank @Size(max = 40) String displayName) {}

  @GetMapping("/me")
  Map<String, Object> me(Principal principal) {
    if (principal == null)
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    return member(principal.getName());
  }

  @PatchMapping("/me")
  Map<String, Object> updateMe(@Valid @RequestBody UpdateProfile body, Principal principal) {
    if (principal == null)
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
    String nextName = body.displayName().strip();
    if (nextName.isEmpty()) throw ApiErrors.bad("Display name is required");
    db.update("UPDATE member SET display_name=? WHERE id=?", nextName, principal.getName());
    return member(principal.getName());
  }

  @PostMapping("/logout")
  Map<String, Boolean> logout(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return Map.of("ok", true);
  }
}
