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
  private final String dummyHash;

  AuthController(
      JdbcTemplate db,
      PasswordEncoder encoder,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf,
      RequestLimits limits) {
    this.db = db;
    this.encoder = encoder;
    this.contexts = contexts;
    this.csrf = csrf;
    this.limits = limits;
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
    // Administrator promotion is an explicit database operation, never granted through public
    // registration.
    db.update(
        "INSERT INTO member(id,email,display_name,password_hash) VALUES (?,?,?,?)",
        id,
        body.email().toLowerCase(Locale.ROOT),
        body.displayName().strip(),
        encoder.encode(body.password()));
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
    signIn(id, (String) row.get("role"), request, response);
    return member(id);
  }

  private void signIn(
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
        "SELECT id, display_name AS \"displayName\", role FROM member WHERE id=?", id);
  }

  @GetMapping("/me")
  Map<String, Object> me(Principal principal) {
    if (principal == null)
      throw new ApiErrors.Failure(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Sign in required");
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
