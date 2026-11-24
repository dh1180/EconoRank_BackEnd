package com.statagora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final String configuredProvider;
  private final String baseUrl;
  private final String apiKey;
  private final String deeplApiKey;
  private final String geminiApiKey;
  private final String geminiModel;
  private final boolean enabled;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  @Autowired
  TranslationService(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${statagora.translation.provider:auto}") String configuredProvider,
      @Value("${statagora.translation.url:}") String baseUrl,
      @Value("${statagora.translation.api-key:}") String apiKey,
      @Value("${statagora.translation.deepl-api-key:}") String deeplApiKey,
      @Value("${statagora.translation.gemini-api-key:}") String geminiApiKey,
      @Value("${statagora.translation.gemini-model:gemini-2.5-flash}") String geminiModel,
      @Value("${statagora.translation.enabled:true}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.configuredProvider =
        configuredProvider == null ? "auto" : configuredProvider.trim().toLowerCase(Locale.ROOT);
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.deeplApiKey = deeplApiKey == null ? "" : deeplApiKey.trim();
    this.geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.trim();
    this.geminiModel =
        geminiModel == null || geminiModel.isBlank() ? "gemini-2.5-flash" : geminiModel.trim();
    this.enabled = enabled;
  }

  /** Convenience constructor for tests using LibreTranslate mock server. */
  TranslationService(
      JdbcTemplate db, ObjectMapper json, String baseUrl, String apiKey, boolean enabled) {
    this(db, json, "auto", baseUrl, apiKey, "", "", "gemini-2.5-flash", enabled);
  }

  String resolveProvider() {
    if (!configuredProvider.isEmpty() && !configuredProvider.equals("auto")) {
      return configuredProvider;
    }
    if (!geminiApiKey.isEmpty()) return "gemini";
    if (!deeplApiKey.isEmpty()) return "deepl";
    if (!baseUrl.isEmpty()) return "libretranslate";
    return "google-gtx";
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

    if (!enabled) {
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE, "Translation service is disabled.");
    }

    String provider = resolveProvider();

    // 2. Translate while keeping [[stat:...]] tokens 100% intact
    String translatedTitle =
        sourceTitle == null || sourceTitle.isBlank()
            ? null
            : callProvider(provider, sourceTitle, normalizedLang);
    String translatedBody = translatePreservingStatTokens(provider, sourceBody, normalizedLang);

    // 3. Persist in DB cache so every post/comment/language is translated only once
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
          provider);
    } catch (DataIntegrityViolationException ignored) {
      // Another concurrent request cached the same translation
    }

    return new Result(translatedTitle, translatedBody, normalizedLang, false, provider);
  }

  String translatePreservingStatTokens(String provider, String text, String targetLang) {
    if (text == null || text.isBlank()) return text == null ? "" : text;
    Matcher m = STAT_TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    int lastEnd = 0;
    while (m.find()) {
      String segment = text.substring(lastEnd, m.start());
      if (!segment.isBlank()) {
        out.append(translateSegmentPreservingWhitespace(provider, segment, targetLang));
      } else {
        out.append(segment);
      }
      out.append(m.group(1));
      lastEnd = m.end();
    }
    String tail = text.substring(lastEnd);
    if (!tail.isBlank()) {
      out.append(translateSegmentPreservingWhitespace(provider, tail, targetLang));
    } else {
      out.append(tail);
    }
    return out.toString();
  }

  private String translateSegmentPreservingWhitespace(
      String provider, String segment, String targetLang) {
    int start = 0;
    int end = segment.length();
    while (start < end && Character.isWhitespace(segment.charAt(start))) start++;
    while (end > start && Character.isWhitespace(segment.charAt(end - 1))) end--;
    String leading = segment.substring(0, start);
    String core = segment.substring(start, end);
    String trailing = segment.substring(end);
    return leading + callProvider(provider, core, targetLang) + trailing;
  }

  private String callProvider(String provider, String text, String targetLang) {
    return switch (provider) {
      case "gemini" -> callGemini(text, targetLang);
      case "deepl" -> callDeepL(text, targetLang);
      case "libretranslate" -> callLibreTranslate(text, targetLang);
      default -> callGoogleGtx(text, targetLang);
    };
  }

  private String callGoogleGtx(String text, String targetLang) {
    try {
      String tl = googleTargetLang(targetLang);
      String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
      String endpoint =
          "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
              + URLEncoder.encode(tl, StandardCharsets.UTF_8)
              + "&dt=t&q="
              + q;
      var req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(12))
              .header("User-Agent", "Mozilla/5.0")
              .header("Accept", "application/json")
              .GET()
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        JsonNode root = json.readTree(res.body());
        JsonNode sentences = root.get(0);
        if (sentences != null && sentences.isArray()) {
          StringBuilder sb = new StringBuilder();
          for (JsonNode part : sentences) {
            if (part.isArray() && part.size() > 0 && !part.get(0).isNull()) {
              sb.append(part.get(0).asText(""));
            }
          }
          String translated = sb.toString();
          if (!translated.isBlank()) return translated;
        }
      }
      log.warn("Google GTX returned status {} body {}", res.statusCode(), res.body());
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Translation service could not translate into '" + targetLang + "'.");
    } catch (ApiErrors.Failure f) {
      throw f;
    } catch (Exception e) {
      log.warn("Google GTX translation failed: {}", e.toString());
      throw new ApiErrors.Failure(
          HttpStatus.SERVICE_UNAVAILABLE, "Translation service is temporarily unavailable.");
    }
  }

  private String callDeepL(String text, String targetLang) {
    try {
      String endpoint =
          deeplApiKey.endsWith(":fx")
              ? "https://api-free.deepl.com/v2/translate"
              : "https://api.deepl.com/v2/translate";
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("text", List.of(text));
      payload.put("target_lang", deeplTargetLang(targetLang));
      var req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "DeepL-Auth-Key " + deeplApiKey)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        JsonNode translations = json.readTree(res.body()).path("translations");
        if (translations.isArray() && translations.size() > 0) {
          String translated = translations.get(0).path("text").asText("");
          if (!translated.isBlank()) return translated;
        }
      }
      log.warn("DeepL returned status {} body {}", res.statusCode(), res.body());
      // Fall back to Google GTX for languages DeepL doesn't support (e.g. Hindi, Thai, Vietnamese)
      return callGoogleGtx(text, targetLang);
    } catch (ApiErrors.Failure f) {
      throw f;
    } catch (Exception e) {
      log.warn("DeepL translation failed, falling back to Google GTX: {}", e.toString());
      return callGoogleGtx(text, targetLang);
    }
  }

  private String callGemini(String text, String targetLang) {
    try {
      String endpoint =
          "https://generativelanguage.googleapis.com/v1beta/models/"
              + URLEncoder.encode(geminiModel, StandardCharsets.UTF_8)
              + ":generateContent?key="
              + URLEncoder.encode(geminiApiKey, StandardCharsets.UTF_8);
      String prompt =
          "Translate the following text into language code '"
              + targetLang
              + "'. Preserve line breaks and tone. Return ONLY the translated text without quotes, labels, or explanations.\n\n"
              + text;
      Map<String, Object> payload =
          Map.of(
              "contents",
              List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
              "generationConfig",
              Map.of("temperature", 0.1));
      var req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        JsonNode candidates = json.readTree(res.body()).path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
          JsonNode parts = candidates.get(0).path("content").path("parts");
          if (parts.isArray() && parts.size() > 0) {
            String translated = parts.get(0).path("text").asText("").trim();
            if (!translated.isBlank()) return translated;
          }
        }
      }
      log.warn("Gemini returned status {} body {}", res.statusCode(), res.body());
      return callGoogleGtx(text, targetLang);
    } catch (ApiErrors.Failure f) {
      throw f;
    } catch (Exception e) {
      log.warn("Gemini translation failed, falling back to Google GTX: {}", e.toString());
      return callGoogleGtx(text, targetLang);
    }
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
          "Translation server is not reachable.");
    }
  }

  static String normalizeTargetLang(String lang) {
    if (lang == null || lang.isBlank()) return "en";
    String lower = lang.trim().toLowerCase(Locale.ROOT);
    if (lower.equals("zh-tw") || lower.equals("zh-hant")) return "zh-tw";
    if (lower.startsWith("zh")) return "zh";
    return lower;
  }

  static String googleTargetLang(String normalizedLang) {
    if ("zh".equals(normalizedLang)) return "zh-CN";
    if ("zh-tw".equals(normalizedLang)) return "zh-TW";
    return normalizedLang;
  }

  static String deeplTargetLang(String normalizedLang) {
    return switch (normalizedLang) {
      case "en" -> "EN-US";
      case "zh" -> "ZH-HANS";
      case "zh-tw" -> "ZH-HANT";
      case "pt" -> "PT-BR";
      default -> normalizedLang.toUpperCase(Locale.ROOT);
    };
  }
}
