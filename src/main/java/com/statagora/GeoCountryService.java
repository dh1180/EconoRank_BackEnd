package com.statagora;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class GeoCountryService {
  private static final Logger log = LoggerFactory.getLogger(GeoCountryService.class);
  private static final Map<String, String> ISO2_TO_ISO3 = buildIsoMap();

  private final ObjectMapper json;
  private final boolean lookupEnabled;
  private final ConcurrentHashMap<String, String> ipCache = new ConcurrentHashMap<>();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  GeoCountryService(
      ObjectMapper json, @Value("${statagora.refresh.enabled:true}") boolean lookupEnabled) {
    this.json = json;
    this.lookupEnabled = lookupEnabled;
  }

  String detectCountryIso3(HttpServletRequest request) {
    // 1. Prefer trusted edge geolocation headers (Cloudflare / Vercel)
    for (String header : List.of("CF-IPCountry", "X-Vercel-IP-Country")) {
      String raw = request.getHeader(header);
      String iso3 = toIso3(raw);
      if (iso3 != null) return iso3;
    }
    if (!lookupEnabled) return null;

    // 2. Resolve client IP (or host public IP for loopback/private dev addresses)
    String ip = clientIp(request);
    String cacheKey = isPrivateOrLocal(ip) ? "LOCAL_HOST" : ip;
    String cached = ipCache.get(cacheKey);
    if (cached != null) return cached.isEmpty() ? null : cached;

    String detected = fetchCountryIso3(cacheKey.equals("LOCAL_HOST") ? null : ip);
    ipCache.put(cacheKey, detected == null ? "" : detected);
    return detected;
  }

  private String fetchCountryIso3(String publicIp) {
    try {
      String url =
          publicIp == null ? "https://api.country.is/" : "https://api.country.is/" + publicIp;
      var req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(3))
              .header("User-Agent", "StatAgora/0.1")
              .GET()
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        String iso2 = json.readTree(res.body()).path("country").asText("");
        return toIso3(iso2);
      }
    } catch (Exception e) {
      log.debug("IP geolocation lookup skipped: {}", e.toString());
    }
    return null;
  }

  static String countryToLanguage(String iso3) {
    if (iso3 == null || iso3.isBlank()) return null;
    return switch (iso3.trim().toUpperCase(Locale.ROOT)) {
      case "KOR" -> "ko";
      case "JPN" -> "ja";
      case "CHN", "TWN", "HKG", "MAC" -> "zh";
      case "ESP", "MEX", "ARG", "COL", "CHL", "PER", "VEN", "ECU", "GTM", "CUB", "DOM", "URY", "PRY", "BOL" -> "es";
      case "FRA", "MCO" -> "fr";
      case "DEU", "AUT", "LIE" -> "de";
      case "BRA", "PRT", "AGO", "MOZ" -> "pt";
      case "RUS", "BLR" -> "ru";
      case "UKR" -> "uk";
      case "SAU", "ARE", "EGY", "QAT", "KWT", "IRQ", "JOR", "LBN", "OMN", "BHR", "MAR", "DZA", "TUN" -> "ar";
      case "IND" -> "hi";
      case "IDN" -> "id";
      case "VNM" -> "vi";
      case "THA" -> "th";
      case "TUR" -> "tr";
      case "ITA", "SMR" -> "it";
      case "NLD" -> "nl";
      case "POL" -> "pl";
      case "SWE" -> "sv";
      case "MYS" -> "ms";
      case "PHL" -> "tl";
      case "ISR" -> "he";
      case "IRN" -> "fa";
      case "PAK" -> "ur";
      case "BGD" -> "bn";
      default -> "en";
    };
  }

  static String toIso3(String iso2) {
    if (iso2 == null) return null;
    String upper = iso2.trim().toUpperCase(Locale.ROOT);
    if (upper.length() == 3 && upper.matches("[A-Z]{3}")) return upper;
    if (upper.length() != 2 || upper.equals("XX") || upper.equals("T1")) return null;
    return ISO2_TO_ISO3.get(upper);
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private static boolean isPrivateOrLocal(String ip) {
    if (ip == null || ip.isBlank()) return true;
    return ip.equals("127.0.0.1")
        || ip.equals("0:0:0:0:0:0:0:1")
        || ip.equals("::1")
        || ip.startsWith("10.")
        || ip.startsWith("192.168.")
        || ip.startsWith("172.16.")
        || ip.startsWith("172.17.")
        || ip.startsWith("172.18.")
        || ip.startsWith("172.19.")
        || ip.startsWith("172.2")
        || ip.startsWith("172.30.")
        || ip.startsWith("172.31.")
        || ip.startsWith("fc")
        || ip.startsWith("fd")
        || ip.startsWith("fe80:");
  }

  private static Map<String, String> buildIsoMap() {
    Map<String, String> map = new HashMap<>();
    for (String iso2 : Locale.getISOCountries()) {
      try {
        String iso3 = new Locale("", iso2).getISO3Country();
        if (iso3 != null && iso3.length() == 3) {
          map.put(iso2.toUpperCase(Locale.ROOT), iso3.toUpperCase(Locale.ROOT));
        }
      } catch (MissingResourceException ignored) {
      }
    }
    map.put("XK", "XKX");
    return Map.copyOf(map);
  }
}
