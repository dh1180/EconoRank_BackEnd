package com.statagora;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TranslationServiceTest {
  @Test
  void preservesStatTokensAndCachesInDatabase() throws Exception {
    AtomicInteger calls = new AtomicInteger(0);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ObjectMapper json = new ObjectMapper();
    server.createContext(
        "/translate",
        exchange -> {
          calls.incrementAndGet();
          var body = json.readTree(exchange.getRequestBody());
          String q = body.path("q").asText();
          String target = body.path("target").asText();
          byte[] resp =
              json.writeValueAsBytes(
                  java.util.Map.of("translatedText", "[" + target + "] " + q));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });
    server.start();

    try {
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:tr_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      JdbcTemplate db = new JdbcTemplate(ds);
      db.execute(
          """
          CREATE TABLE content_translation (
            target_type VARCHAR(16) NOT NULL,
            target_id VARCHAR(36) NOT NULL,
            target_lang VARCHAR(8) NOT NULL,
            translated_title VARCHAR(240),
            translated_body TEXT NOT NULL,
            provider VARCHAR(40) NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (target_type, target_id, target_lang)
          )
          """);

      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      TranslationService svc = new TranslationService(db, json, url, "", true);

      String rawBody =
          "한국 GDP 전망입니다.\n[[stat:country:KOR:gdp:2026]]\n애플 시총도 확인하세요 [[stat:company:AAPL]]";

      var first = svc.translate("post", "p1", "경제 토론", rawBody, "ja");
      assertFalse(first.cached());
      assertEquals("[ja] 경제 토론", first.title());
      assertTrue(first.body().contains("[[stat:country:KOR:gdp:2026]]"));
      assertTrue(first.body().contains("[[stat:company:AAPL]]"));
      assertTrue(first.body().contains("[ja] 한국 GDP 전망입니다."));
      int callsAfterFirst = calls.get();
      assertEquals(3, callsAfterFirst); // title + 2 text chunks around stat tokens

      // Second call must hit DB cache with zero HTTP calls
      var second = svc.translate("post", "p1", "경제 토론", rawBody, "ja");
      assertTrue(second.cached());
      assertEquals(first.title(), second.title());
      assertEquals(first.body(), second.body());
      assertEquals(callsAfterFirst, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void resolvesProviderPriorityAndLanguageCodes() {
    ObjectMapper json = new ObjectMapper();
    assertEquals(
        "google-gtx",
        new TranslationService(null, json, "auto", "", "", "", "", "gemini-2.5-flash", true)
            .resolveProvider());
    assertEquals(
        "libretranslate",
        new TranslationService(
                null, json, "auto", "http://localhost:5000", "", "", "", "gemini-2.5-flash", true)
            .resolveProvider());
    assertEquals(
        "deepl",
        new TranslationService(null, json, "auto", "", "", "key:fx", "", "gemini-2.5-flash", true)
            .resolveProvider());
    assertEquals(
        "gemini",
        new TranslationService(
                null, json, "auto", "", "", "key:fx", "gem-key", "gemini-2.5-flash", true)
            .resolveProvider());

    assertEquals("zh-CN", TranslationService.googleTargetLang("zh"));
    assertEquals("zh-TW", TranslationService.googleTargetLang("zh-tw"));
    assertEquals("EN-US", TranslationService.deeplTargetLang("en"));
    assertEquals("ZH-HANS", TranslationService.deeplTargetLang("zh"));
  }
}
