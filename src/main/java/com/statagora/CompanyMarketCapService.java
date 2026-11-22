package com.statagora;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class CompanyMarketCapService {
  static final String SOURCE_NAME = "CompaniesMarketCap";
  static final String SOURCE_URL = "https://companiesmarketcap.com/";
  static final String CSV_URL = "https://companiesmarketcap.com/?download=csv";
  static final int REFRESH_INTERVAL_MINUTES = 5;
  private static final int MAX_COMPANIES = 500;
  private static final int MAX_CSV_BYTES = 15_000_000;
  private static final Logger log = LoggerFactory.getLogger(CompanyMarketCapService.class);

  record Company(
      int rank,
      String name,
      String symbol,
      BigDecimal marketCap,
      BigDecimal priceUsd,
      String country,
      String countryCode) {}

  record Snapshot(List<Company> items, Instant updatedAt) {}

  private static final Map<String, String> COUNTRY_ALIASES =
      Map.ofEntries(
          Map.entry("united states", "USA"),
          Map.entry("usa", "USA"),
          Map.entry("south korea", "KOR"),
          Map.entry("s. korea", "KOR"),
          Map.entry("korea, rep.", "KOR"),
          Map.entry("china", "CHN"),
          Map.entry("taiwan", "TWN"),
          Map.entry("hong kong", "HKG"),
          Map.entry("united kingdom", "GBR"),
          Map.entry("uk", "GBR"),
          Map.entry("saudi arabia", "SAU"),
          Map.entry("japan", "JPN"),
          Map.entry("germany", "DEU"),
          Map.entry("france", "FRA"),
          Map.entry("india", "IND"),
          Map.entry("canada", "CAN"),
          Map.entry("switzerland", "CHE"),
          Map.entry("netherlands", "NLD"),
          Map.entry("australia", "AUS"),
          Map.entry("ireland", "IRL"),
          Map.entry("denmark", "DNK"),
          Map.entry("spain", "ESP"),
          Map.entry("italy", "ITA"),
          Map.entry("brazil", "BRA"),
          Map.entry("sweden", "SWE"),
          Map.entry("singapore", "SGP"),
          Map.entry("russia", "RUS"),
          Map.entry("mexico", "MEX"),
          Map.entry("belgium", "BEL"),
          Map.entry("indonesia", "IDN"),
          Map.entry("norway", "NOR"),
          Map.entry("israel", "ISR"),
          Map.entry("uae", "ARE"),
          Map.entry("united arab emirates", "ARE"),
          Map.entry("finland", "FIN"),
          Map.entry("austria", "AUT"),
          Map.entry("poland", "POL"),
          Map.entry("thailand", "THA"),
          Map.entry("turkey", "TUR"),
          Map.entry("türkiye", "TUR"),
          Map.entry("south africa", "ZAF"),
          Map.entry("malaysia", "MYS"),
          Map.entry("argentina", "ARG"),
          Map.entry("luxembourg", "LUX"),
          Map.entry("vietnam", "VNM"),
          Map.entry("philippines", "PHL"),
          Map.entry("chile", "CHL"),
          Map.entry("colombia", "COL"),
          Map.entry("czech republic", "CZE"),
          Map.entry("czechia", "CZE"),
          Map.entry("portugal", "PRT"),
          Map.entry("greece", "GRC"),
          Map.entry("hungary", "HUN"),
          Map.entry("new zealand", "NZL"),
          Map.entry("qatar", "QAT"),
          Map.entry("kuwait", "KWT"),
          Map.entry("egypt", "EGY"),
          Map.entry("nigeria", "NGA"),
          Map.entry("kazakhstan", "KAZ"),
          Map.entry("peru", "PER"),
          Map.entry("bermuda", "BMU"),
          Map.entry("cayman islands", "CYM"));

  private final JdbcTemplate db;
  private final boolean refreshEnabled;
  private final AtomicReference<Snapshot> cache = new AtomicReference<>();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  CompanyMarketCapService(
      JdbcTemplate db, @Value("${statagora.refresh.enabled:true}") boolean refreshEnabled) {
    this.db = db;
    this.refreshEnabled = refreshEnabled;
  }

  @Scheduled(
      initialDelayString = "${statagora.companies.initial-delay-ms:2000}",
      fixedDelayString = "${statagora.companies.delay-ms:300000}")
  public synchronized void scheduledRefresh() {
    if (!refreshEnabled) return;
    try {
      refreshNow();
    } catch (Exception e) {
      log.warn("Company market cap refresh failed: {}", e.toString());
    }
  }

  public Snapshot current() {
    Snapshot existing = cache.get();
    if (existing != null
        && Duration.between(existing.updatedAt(), Instant.now())
                .compareTo(Duration.ofMinutes(REFRESH_INTERVAL_MINUTES))
            < 0) {
      return existing;
    }
    if (refreshEnabled) {
      synchronized (this) {
        Snapshot latest = cache.get();
        if (latest == null
            || Duration.between(latest.updatedAt(), Instant.now())
                    .compareTo(Duration.ofMinutes(REFRESH_INTERVAL_MINUTES))
                >= 0) {
          try {
            return refreshNow();
          } catch (Exception e) {
            log.warn("On-demand company market cap refresh failed: {}", e.toString());
            if (latest != null) return latest;
          }
        } else {
          return latest;
        }
      }
    }
    return cache.get();
  }

  Snapshot refreshNow() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(CSV_URL))
            .timeout(Duration.ofSeconds(25))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/130.0.0.0 Safari/537.36")
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    byte[] bytes;
    try (var body = response.body()) {
      if (response.statusCode() != 200)
        throw new IllegalStateException("Companies CSV HTTP " + response.statusCode());
      bytes = body.readNBytes(MAX_CSV_BYTES + 1);
      if (bytes.length > MAX_CSV_BYTES) throw new IllegalStateException("Companies CSV too large");
    }
    String csv = new String(bytes, StandardCharsets.UTF_8);
    List<Company> parsed = parseCsv(csv, dbCountryLookup());
    if (parsed.size() < 20) throw new IllegalStateException("Incomplete companies CSV");
    Snapshot next = new Snapshot(parsed, Instant.now());
    cache.set(next);
    return next;
  }

  private Map<String, String> dbCountryLookup() {
    Map<String, String> map = new HashMap<>(COUNTRY_ALIASES);
    try {
      for (var row : db.queryForList("SELECT code, name FROM country")) {
        String code = Objects.toString(row.get("code"), "");
        String name = Objects.toString(row.get("name"), "").trim().toLowerCase(Locale.ROOT);
        if (!code.isEmpty() && !name.isEmpty()) map.putIfAbsent(name, code);
      }
    } catch (Exception ignored) {
    }
    return map;
  }

  static List<Company> parseCsv(String csv, Map<String, String> countryLookup) {
    List<Company> result = new ArrayList<>();
    String[] lines = csv.split("\\r?\\n");
    if (lines.length < 2) return result;
    int rankCounter = 0;
    for (int i = 1; i < lines.length && result.size() < MAX_COMPANIES; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) continue;
      List<String> cols = parseCsvLine(line);
      if (cols.size() < 6) continue;
      String name = cols.get(1).trim();
      String symbol = cols.get(2).trim();
      String capText = cols.get(3).trim();
      String priceText = cols.get(4).trim();
      String country = cols.get(5).trim();
      if (name.isEmpty() || capText.isEmpty()) continue;
      BigDecimal marketCap;
      BigDecimal priceUsd;
      try {
        marketCap = new BigDecimal(capText);
        priceUsd = priceText.isEmpty() ? BigDecimal.ZERO : new BigDecimal(priceText);
      } catch (NumberFormatException e) {
        continue;
      }
      if (marketCap.signum() <= 0) continue;
      rankCounter++;
      String code = countryLookup.getOrDefault(country.toLowerCase(Locale.ROOT), "");
      result.add(new Company(rankCounter, name, symbol, marketCap, priceUsd, country, code));
    }
    return List.copyOf(result);
  }

  static List<String> parseCsvLine(String line) {
    List<String> fields = new ArrayList<>(6);
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
      } else {
        if (c == '"') {
          inQuotes = true;
        } else if (c == ',') {
          fields.add(current.toString());
          current.setLength(0);
        } else {
          current.append(c);
        }
      }
    }
    fields.add(current.toString());
    return fields;
  }
}
