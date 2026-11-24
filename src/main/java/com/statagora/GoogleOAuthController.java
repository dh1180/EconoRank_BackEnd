package com.statagora;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class GoogleOAuthController {
  private static final Logger log = LoggerFactory.getLogger(GoogleOAuthController.class);
  private static final String SESSION_STATE = "google_oauth_state";
  private static final String SESSION_LANG = "google_oauth_lang";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate db;
  private final AuthController auth;
  private final GeoCountryService geo;
  private final AdminRegistry admins;
  private final PasswordEncoder encoder;
  private final ObjectMapper json;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  GoogleOAuthController(
      JdbcTemplate db,
      AuthController auth,
      GeoCountryService geo,
      AdminRegistry admins,
      PasswordEncoder encoder,
      ObjectMapper json,
      @Value("${statagora.oauth.google.client-id:}") String clientId,
      @Value("${statagora.oauth.google.client-secret:}") String clientSecret,
      @Value("${statagora.oauth.google.redirect-uri:https://econorank.com/login/oauth2/code/google}")
          String redirectUri) {
    this.db = db;
    this.auth = auth;
    this.geo = geo;
    this.admins = admins;
    this.encoder = encoder;
    this.json = json;
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
  }

  private boolean isConfigured() {
    return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
  }

  private static String safeLang(String raw) {
    if (raw != null && raw.matches("[a-z]{2}")) return raw;
    return "en";
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  private String publicOrigin() {
    try {
      URI u = URI.create(redirectUri);
      if (u.getScheme() != null && u.getHost() != null) {
        return u.getPort() > 0
            ? u.getScheme() + "://" + u.getHost() + ":" + u.getPort()
            : u.getScheme() + "://" + u.getHost();
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private void redirectCommunity(HttpServletResponse response, String loc, String query)
      throws IOException {
    String path = publicOrigin() + "/" + loc + "/community" + (query == null ? "" : query);
    response.sendRedirect(path);
  }

  @GetMapping("/providers")
  Map<String, Boolean> providers() {
    return Map.of("google", isConfigured());
  }

  @GetMapping("/google/start")
  void start(
      @RequestParam(defaultValue = "en") String lang,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String loc = safeLang(lang);
    if (!isConfigured()) {
      redirectCommunity(response, loc, "?authError=google_not_configured");
      return;
    }
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    HttpSession session = request.getSession(true);
    session.setAttribute(SESSION_STATE, state);
    session.setAttribute(SESSION_LANG, loc);

    String url =
        "https://accounts.google.com/o/oauth2/v2/auth"
            + "?client_id="
            + enc(clientId)
            + "&redirect_uri="
            + enc(redirectUri)
            + "&response_type=code"
            + "&scope="
            + enc("openid email profile")
            + "&state="
            + enc(state)
            + "&prompt=select_account";
    response.sendRedirect(url);
  }

  @GetMapping("/google/callback")
  void callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    HttpSession session = request.getSession(false);
    String loc =
        session != null ? safeLang((String) session.getAttribute(SESSION_LANG)) : "en";
    String expectedState =
        session != null ? (String) session.getAttribute(SESSION_STATE) : null;
    if (session != null) {
      session.removeAttribute(SESSION_STATE);
      session.removeAttribute(SESSION_LANG);
    }

    if (!isConfigured()
        || error != null
        || code == null
        || code.isBlank()
        || state == null
        || expectedState == null
        || !MessageDigest.isEqual(
            state.getBytes(StandardCharsets.UTF_8),
            expectedState.getBytes(StandardCharsets.UTF_8))) {
      redirectCommunity(response, loc, "?authError=google_failed");
      return;
    }

    try {
      String form =
          "code="
              + enc(code)
              + "&client_id="
              + enc(clientId)
              + "&client_secret="
              + enc(clientSecret)
              + "&redirect_uri="
              + enc(redirectUri)
              + "&grant_type=authorization_code";
      var tokenReq =
          HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      var tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
      if (tokenRes.statusCode() != 200) {
        log.warn("Google token exchange failed: {}", tokenRes.statusCode());
        redirectCommunity(response, loc, "?authError=google_failed");
        return;
      }
      String accessToken = json.readTree(tokenRes.body()).path("access_token").asText("");
      if (accessToken.isBlank()) {
        redirectCommunity(response, loc, "?authError=google_failed");
        return;
      }

      var userReq =
          HttpRequest.newBuilder(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + accessToken)
              .GET()
              .build();
      var userRes = http.send(userReq, HttpResponse.BodyHandlers.ofString());
      if (userRes.statusCode() != 200) {
        log.warn("Google userinfo failed: {}", userRes.statusCode());
        redirectCommunity(response, loc, "?authError=google_failed");
        return;
      }
      var profile = json.readTree(userRes.body());
      String sub = profile.path("sub").asText("").trim();
      String email = profile.path("email").asText("").trim().toLowerCase(Locale.ROOT);
      String rawName = profile.path("name").asText("").trim();
      if (sub.isEmpty() || email.isEmpty()) {
        redirectCommunity(response, loc, "?authError=google_failed");
        return;
      }

      String displayName =
          rawName.isEmpty() ? email.substring(0, Math.min(email.indexOf('@'), 40)) : rawName;
      if (displayName.length() > 40) displayName = displayName.substring(0, 40).trim();
      if (displayName.isEmpty()) displayName = "Member";

      var existing =
          db.queryForList(
              "SELECT id, role, google_sub FROM member WHERE google_sub=? OR email=?", sub, email);
      String id;
      String role;
      if (!existing.isEmpty()) {
        var row = existing.get(0);
        id = (String) row.get("id");
        role = (String) row.get("role");
        if (row.get("google_sub") == null) {
          db.update("UPDATE member SET google_sub=? WHERE id=?", sub, id);
        }
        if (admins.isConfiguredAdmin(email) && !"ADMIN".equals(role)) {
          db.update("UPDATE member SET role='ADMIN' WHERE id=?", id);
          role = "ADMIN";
        }
      } else {
        id = UUID.randomUUID().toString();
        role = admins.isConfiguredAdmin(email) ? "ADMIN" : "USER";
        String countryCode = geo.detectCountryIso3(request);
        String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID();
        db.update(
            "INSERT INTO member(id,email,display_name,password_hash,role,country_code,google_sub)"
                + " VALUES (?,?,?,?,?,?,?)",
            id,
            email,
            displayName,
            encoder.encode(randomPassword),
            role,
            countryCode,
            sub);
      }

      auth.signIn(id, role, request, response);
      redirectCommunity(response, loc, null);
    } catch (Exception e) {
      log.warn("Google OAuth callback error: {}", e.toString());
      redirectCommunity(response, loc, "?authError=google_failed");
    }
  }
}
