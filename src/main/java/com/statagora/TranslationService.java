package com.statagora;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class TranslationService {
  private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
  private static final Pattern STAT_TOKEN =
      Pattern.compile(
          "(\\[\\[stat:(?:country:[A-Z]{3}:(?:gdp|gdppc|ppp):\\d{4}|company:[A-Za-z0-9.\\-^]{1,20})\\]\\])");

  record Result(
      String title, String body, String targetLang, boolean cached, String provider) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String baseUrl;
  private final String apiKey;
  private final boolean enabled;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  TranslationService(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${statagora.translation.url:http://127.0.0.1:5000}") String baseUrl,
      @Value("${statagora.translation.api-key:}") String apiKey,
      @Value("${statagora.translation.enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.enabled = enabled;
  }

  Result translate(
      String targetType,
      String targetId,
      String sourceTitle,
      String sourceBody,
      String targetLang) {
    String normalizedLang = normalizeTargetLang(targetLang);

    // 1. Check DB cache first (zero external call on cache hit)
    var cached =
        db.queryForList(
            "SELECT translated_title, translated_body, provider FROM content_translation"
                + " WHERE target_type=? AND target_id=? AND target_lang=?",
            targetType,
            targetId,
            normalizedLang);
    if (!cached.isEmpty()) {
      var row = cached.get(0);
      return new Result(
          (String) row.get("translated_title"),
          (String) row.get("translated_body"),
          normalizedLang,
          true,
          (String) row.get("provider"));
    }

    if (!enabled || baseUrl.isBlank()) {
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE, "Translation service is disabled.");
    }

    // 2. Call LibreTranslate, keeping [[stat:...]] tokens 100% intact
    String translatedTitle =
        sourceTitle == null || sourceTitle.isBlank()
            ? null
            : callLibreTranslate(sourceTitle, normalizedLang);
    String translatedBody = translatePreservingStatTokens(sourceBody, normalizedLang);

    // 3. Persist in DB cache
    try {
      db.update(
          "INSERT INTO content_translation"
              + " (target_type, target_id, target_lang, translated_title, translated_body, provider)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          targetType,
          targetId,
          normalizedLang,
          translatedTitle,
          translatedBody,
          "libretranslate");
    } catch (DataIntegrityViolationException ignored) {
      // Another concurrent request cached the same translation
    }

    return new Result(translatedTitle, translatedBody, normalizedLang, false, "libretranslate");
  }

  String translatePreservingStatTokens(String text, String targetLang) {
    if (text == null || text.isBlank()) return text == null ? "" : text;
    Matcher m = STAT_TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    int lastEnd = 0;
    while (m.find()) {
      String segment = text.substring(lastEnd, m.start());
      if (!segment.isBlank()) {
        out.append(translateSegmentPreservingWhitespace(segment, targetLang));
      } else {
        out.append(segment);
      }
      out.append(m.group(1));
      lastEnd = m.end();
    }
    String tail = text.substring(lastEnd);
    if (!tail.isBlank()) {
      out.append(translateSegmentPreservingWhitespace(tail, targetLang));
    } else {
      out.append(tail);
    }
    return out.toString();
  }

  private String translateSegmentPreservingWhitespace(String segment, String targetLang) {
    int start = 0;
    int end = segment.length();
    while (start < end && Character.isWhitespace(segment.charAt(start))) start++;
    while (end > start && Character.isWhitespace(segment.charAt(end - 1))) end--;
    String leading = segment.substring(0, start);
    String core = segment.substring(start, end);
    String trailing = segment.substring(end);
    return leading + callLibreTranslate(core, targetLang) + trailing;
  }

  private String callLibreTranslate(String text, String targetLang) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("q", text);
      payload.put("source", "auto");
      payload.put("target", targetLang);
      payload.put("format", "text");
      if (!apiKey.isEmpty()) {
        payload.put("api_key", apiKey);
      }
      var req =
          HttpRequest.newBuilder(URI.create(baseUrl + "/translate"))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        String translated = json.readTree(res.body()).path("translatedText").asText("");
        if (!translated.isBlank()) return translated;
      }
      log.warn("LibreTranslate returned status {} body {}", res.statusCode(), res.body());
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Translation server could not translate into '" + targetLang + "'.");
    } catch (ApiErrors.Failure f) {
      throw f;
    } catch (Exception e) {
      log.warn("LibreTranslate unreachable at {}: {}", baseUrl, e.toString());
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Local translation server (LibreTranslate) is not running.");
    }
  }

  static String normalizeTargetLang(String lang) {
    if (lang == null || lang.isBlank()) return "en";
    String lower = lang.trim().toLowerCase(Locale.ROOT);
    // LibreTranslate uses "zh" for Simplified Chinese
    if (lower.startsWith("zh")) return "zh";
    return lower;
  }
}
