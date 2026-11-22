package com.statagora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "statagora.refresh.enabled=false",
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
      "spring.datasource.username=${TEST_DATABASE_USERNAME:sa}",
      "spring.datasource.password=${TEST_DATABASE_PASSWORD:}"
    })
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired EconomicSnapshotStore snapshots;

  @Test
  void latestImfReleaseAndReferenceYearAreSeparateFromWorldBankActuals() throws Exception {
    api.perform(get("/api/rankings?indicator=gdp&size=1"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.year").value(java.time.Year.now(java.time.ZoneOffset.UTC).getValue()))
        .andExpect(jsonPath("$.provider").value("imf"))
        .andExpect(jsonPath("$.items[0].publishedAt").value("2026-10-13T02:00:00Z"))
        .andExpect(jsonPath("$.items[0].status").value("ESTIMATE_OR_FORECAST"));
    api.perform(get("/api/rankings?indicator=gdp&source=worldbank&year=2024&size=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("worldbank"))
        .andExpect(jsonPath("$.items[0].status").value("ACTUAL"));
    api.perform(get("/api/compare?countries=USA,KOR&year=2026"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4));
    api.perform(get("/api/rankings?source=invalid")).andExpect(status().isBadRequest());
    api.perform(get("/api/rankings?indicator=exports&source=imf"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void invalidRefreshCannotReplaceLastGoodDataAndRevisionsAreAtomic() throws Exception {
    int before = db.queryForObject("SELECT COUNT(*) FROM observation", Integer.class);
    var input = new org.springframework.core.io.ClassPathResource("data/imf-weo.json");
    EconomicSnapshotStore.Snapshot original;
    try (var stream = input.getInputStream()) {
      original = json.readValue(stream, EconomicSnapshotStore.Snapshot.class);
    }
    var invalid =
        new EconomicSnapshotStore.Snapshot(
            "imf",
            original.source(),
            original.publishedAt(),
            original.edition(),
            original.sourceUrl(),
            original.retrievedAt(),
            "a".repeat(64),
            original.observations().subList(0, 1));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshots.apply(invalid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM observation", Integer.class))
        .isEqualTo(before);
    assertThat(snapshots.apply(original)).isFalse();
    var revision =
        new EconomicSnapshotStore.Snapshot(
            "imf",
            original.source(),
            original.publishedAt(),
            original.edition(),
            original.sourceUrl(),
            original.retrievedAt(),
            "b".repeat(64),
            original.observations());
    assertThat(snapshots.apply(revision)).isTrue();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM observation", Integer.class))
        .isEqualTo(before);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM observation WHERE provider='worldbank'", Integer.class))
        .isGreaterThan(1000);
  }

  String payload(Object body) throws Exception {
    return json.writeValueAsString(body);
  }

  record Account(String id, MockHttpSession session) {}

  Account register() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var result =
        api.perform(
                post("/api/auth/register")
                    .with(csrf())
                    .with(
                        request -> {
                          request.setRemoteAddr(UUID.randomUUID().toString());
                          return request;
                        })
                    .contentType("application/json")
                    .content(
                        payload(
                            Map.of(
                                "email",
                                email,
                                "password",
                                "secure-pass-123",
                                "displayName",
                                "Member"))))
            .andExpect(status().isCreated())
            .andReturn();
    return new Account(
        json.readTree(result.getResponse().getContentAsString()).path("id").asText(),
        (MockHttpSession) result.getRequest().getSession());
  }

  String createPost(Account account) throws Exception {
    var response =
        api.perform(
                post("/api/posts")
                    .session(account.session)
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        payload(
                            Map.of(
                                "spaceId",
                                "global",
                                "title",
                                "GDP discussion",
                                "body",
                                "<script>alert(1)</script> is plain text",
                                "language",
                                "en"))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(response.getResponse().getContentAsString()).path("id").asText();
  }

  @Test
  void rankingsExcludeAggregatesAndUseSameYear() throws Exception {
    var result =
        api.perform(get("/api/rankings?indicator=gdp&year=2024&size=100&source=worldbank"))
            .andExpect(status().isOk())
            .andReturn();
    var body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("total").asInt()).isGreaterThan(100);
    double previous = Double.MAX_VALUE;
    for (var row : body.path("items")) {
      assertThat(row.path("year").asInt()).isEqualTo(2024);
      assertThat(row.path("code").asText()).isNotIn("WLD", "HIC", "EUU");
      assertThat(row.path("value").asDouble()).isLessThanOrEqualTo(previous);
      previous = row.path("value").asDouble();
      assertThat(row.path("sourceUrl").asText()).startsWith("https://data.worldbank.org/");
      assertThat(row.path("retrievedAt").asText()).isNotBlank();
    }
  }

  @Test
  void invalidFiltersAndUnknownCountries() throws Exception {
    api.perform(get("/api/rankings?indicator=bad")).andExpect(status().isBadRequest());
    api.perform(get("/api/rankings?size=101")).andExpect(status().isBadRequest());
    api.perform(get("/api/rankings?page=-1")).andExpect(status().isBadRequest());
    api.perform(get("/api/compare?countries=USA,XXX")).andExpect(status().isNotFound());
    api.perform(get("/api/compare?countries=USA,USA")).andExpect(status().isBadRequest());
    api.perform(get("/api/compare?countries=USA,KOR&year=2024"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(6));
  }

  @Test
  void sessionsCsrfAndPasswordHashing() throws Exception {
    var member = register();
    api.perform(get("/api/auth/me").session(member.session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("USER"));
    assertThat(
            db.queryForObject(
                "SELECT password_hash FROM member WHERE id=?", String.class, member.id))
        .startsWith("$2a$");
    api.perform(
            post("/api/posts")
                .session(member.session)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    api.perform(post("/api/posts").with(csrf()).contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    api.perform(post("/api/auth/logout").session(member.session).with(csrf()))
        .andExpect(status().isOk());
    api.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void actualCsrfTokenWorksAndRotatesAfterLogin() throws Exception {
    var account = register();
    var csrfResponse =
        api.perform(get("/api/auth/csrf").session(account.session))
            .andExpect(status().isOk())
            .andReturn();
    var token = json.readTree(csrfResponse.getResponse().getContentAsString());
    api.perform(
            post("/api/posts")
                .session(account.session)
                .header(token.path("headerName").asText(), token.path("token").asText())
                .contentType("application/json")
                .content(
                    payload(
                        Map.of(
                            "spaceId",
                            "global",
                            "title",
                            "CSRF test",
                            "body",
                            "Verified",
                            "language",
                            "en"))))
        .andExpect(status().isCreated());
  }

  @Test
  void ownershipCommentsReportsAndModeration() throws Exception {
    var owner = register();
    var other = register();
    String id = createPost(owner);
    api.perform(delete("/api/posts/" + id).session(other.session).with(csrf()))
        .andExpect(status().isNotFound());
    api.perform(
            post("/api/posts/" + id + "/comments")
                .session(other.session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"body\":\"Thoughtful reply\"}"))
        .andExpect(status().isCreated());
    String report = payload(Map.of("targetType", "post", "targetId", id, "reason", "Spam"));
    api.perform(
            post("/api/reports")
                .session(other.session)
                .with(csrf())
                .contentType("application/json")
                .content(report))
        .andExpect(status().isCreated());
    api.perform(
            post("/api/reports")
                .session(other.session)
                .with(csrf())
                .contentType("application/json")
                .content(report))
        .andExpect(status().isConflict());
    api.perform(get("/api/admin/reports").session(other.session)).andExpect(status().isForbidden());
    // Use a real member identity so the moderation audit FK is exercised.
    api.perform(
            post("/api/admin/hide")
                .with(user(owner.id).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(payload(Map.of("targetType", "post", "targetId", id))))
        .andExpect(status().isOk());
    api.perform(get("/api/posts/" + id)).andExpect(status().isNotFound());
    assertThat(db.queryForObject("SELECT status FROM report WHERE target_id=?", String.class, id))
        .isEqualTo("RESOLVED");
  }

  @Test
  void blockedAuthorsDisappearAndCanBeUnblocked() throws Exception {
    var author = register();
    var reader = register();
    String id = createPost(author);
    api.perform(post("/api/blocks/" + author.id).session(reader.session).with(csrf()))
        .andExpect(status().isOk());
    api.perform(get("/api/posts/" + id).session(reader.session)).andExpect(status().isNotFound());
    api.perform(delete("/api/blocks/" + author.id).session(reader.session).with(csrf()))
        .andExpect(status().isOk());
    api.perform(get("/api/posts/" + id).session(reader.session)).andExpect(status().isOk());
  }

  @Test
  void deletedPostsRejectCommentsAndInvalidPayloads() throws Exception {
    var member = register();
    String id = createPost(member);
    api.perform(delete("/api/posts/" + id).session(member.session).with(csrf()))
        .andExpect(status().isOk());
    api.perform(
            post("/api/posts/" + id + "/comments")
                .session(member.session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"body\":\"reply\"}"))
        .andExpect(status().isNotFound());
    api.perform(
            post("/api/posts")
                .session(member.session)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"spaceId\":\"global\",\"title\":\"\",\"body\":\"x\",\"language\":\"xx\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidLoginAndRateLimit() throws Exception {
    for (int i = 0; i < 10; i++)
      api.perform(
              post("/api/auth/login")
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized());
    api.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void successfulLoginUsesPersistedAccountAndRejectsWrongPassword() throws Exception {
    var account = register();
    String email =
        db.queryForObject("SELECT email FROM member WHERE id=?", String.class, account.id);
    var loggedIn =
        api.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .with(
                        request -> {
                          request.setRemoteAddr("login-success-test");
                          return request;
                        })
                    .contentType("application/json")
                    .content(payload(Map.of("email", email, "password", "secure-pass-123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(account.id))
            .andReturn();
    api.perform(get("/api/auth/me").session((MockHttpSession) loggedIn.getRequest().getSession()))
        .andExpect(status().isOk());
    api.perform(
            post("/api/auth/login")
                .with(csrf())
                .with(
                    request -> {
                      request.setRemoteAddr("login-success-test");
                      return request;
                    })
                .contentType("application/json")
                .content(payload(Map.of("email", email, "password", "incorrect"))))
        .andExpect(status().isUnauthorized());
  }
}
