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
import org.springframework.stereotype.Service;

@Service
class CompanyMarketCapService {
  static final String SOURCE_NAME = "CompaniesMarketCap";
  static final String SOURCE_URL = "https://companiesmarketcap.com/";
  static final String CSV_URL = "https://companiesmarketcap.com/?download=csv";
  static final int REFRESH_INTERVAL_MINUTES = 15;
  private static final int MAX_COMPANIES = 15000;
  private static final int MAX_CSV_BYTES = 15_000_000;
  private static final Logger log = LoggerFactory.getLogger(CompanyMarketCapService.class);

  record Company(
      int rank,
      String name,
      String symbol,
      BigDecimal marketCap,
      BigDecimal priceUsd,
      String country,
      String countryCode,
      String exchange,
      String region) {}

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

  private static final Map<String, String> EXCHANGE_SUFFIXES =
      Map.ofEntries(
          Map.entry("KS", "KRX"),
          Map.entry("KQ", "KRX"),
          Map.entry("T", "TSE"),
          Map.entry("HK", "HKEX"),
          Map.entry("SS", "SSE"),
          Map.entry("SZ", "SZSE"),
          Map.entry("TW", "TWSE"),
          Map.entry("TWO", "TWSE"),
          Map.entry("SR", "Tadawul"),
          Map.entry("L", "LSE"),
          Map.entry("DE", "XETRA"),
          Map.entry("F", "XETRA"),
          Map.entry("PA", "Euronext Paris"),
          Map.entry("AS", "Euronext Amsterdam"),
          Map.entry("BR", "Euronext Brussels"),
          Map.entry("SW", "SIX Swiss"),
          Map.entry("NS", "NSE India"),
          Map.entry("BO", "NSE India"),
          Map.entry("TO", "TSX"),
          Map.entry("V", "TSX"),
          Map.entry("AX", "ASX"),
          Map.entry("SA", "B3"),
          Map.entry("CO", "Nasdaq Copenhagen"),
          Map.entry("ST", "Nasdaq Stockholm"),
          Map.entry("HE", "Nasdaq Helsinki"),
          Map.entry("OL", "Oslo Børs"),
          Map.entry("MC", "BME Madrid"),
          Map.entry("MI", "Borsa Italiana"),
          Map.entry("SI", "SGX"),
          Map.entry("JK", "IDX"),
          Map.entry("BK", "SET"),
          Map.entry("WA", "GPW Warsaw"),
          Map.entry("TA", "TASE"),
          Map.entry("JO", "JSE"),
          Map.entry("MX", "BMV"),
          Map.entry("AE", "UAE (ADX/DFM)"),
          Map.entry("KW", "Boursa Kuwait"),
          Map.entry("QA", "QSE Qatar"),
          Map.entry("IS", "Borsa Istanbul"),
          Map.entry("KL", "Bursa Malaysia"),
          Map.entry("VI", "Vienna"),
          Map.entry("LS", "Euronext Lisbon"),
          Map.entry("NZ", "NZX"),
          Map.entry("AT", "Athens"),
          Map.entry("PS", "PSE Manila"),
          Map.entry("VN", "HOSE Vietnam"),
          Map.entry("SN", "Santiago"),
          Map.entry("BA", "BYMA"));

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

  /** Lazy request-driven 15-minute TTL cache (zero background traffic when idle). */
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

  private static final Set<String> NORTH_AMERICA = Set.of("USA", "CAN", "MEX", "BMU", "CYM");
  private static final Set<String> EUROPE =
      Set.of(
          "GBR", "DEU", "FRA", "CHE", "NLD", "IRL", "DNK", "ESP", "ITA", "SWE", "BEL", "NOR", "FIN",
          "AUT", "POL", "LUX", "CZE", "PRT", "GRC", "HUN", "RUS", "TUR", "UKR", "ROU", "ISL", "CYP",
          "MLT", "HRV", "BGR", "SVK", "SVN", "LTU", "LVA", "EST", "SRB", "JEY", "GGY", "IMN");
  private static final Set<String> ASIA =
      Set.of(
          "CHN", "JPN", "KOR", "TWN", "IND", "HKG", "SGP", "IDN", "THA", "MYS", "VNM", "PHL", "KAZ",
          "PAK", "BGD", "LKA", "MAC", "MNG");
  private static final Set<String> MIDDLE_EAST =
      Set.of("SAU", "ARE", "ISR", "QAT", "KWT", "OMN", "BHR", "JOR", "EGY");
  private static final Set<String> OCEANIA = Set.of("AUS", "NZL");
  private static final Set<String> LATIN_AMERICA =
      Set.of("BRA", "ARG", "CHL", "COL", "PER", "URY", "PAN", "CRI");
  private static final Set<String> AFRICA = Set.of("ZAF", "NGA", "MAR", "KEN", "GHA", "MUS");

  static String resolveRegion(String countryCode, String exchange) {
    if (countryCode != null && !countryCode.isBlank()) {
      String c = countryCode.toUpperCase(Locale.ROOT);
      if (NORTH_AMERICA.contains(c)) return "NORTH_AMERICA";
      if (EUROPE.contains(c)) return "EUROPE";
      if (ASIA.contains(c)) return "ASIA";
      if (MIDDLE_EAST.contains(c)) return "MIDDLE_EAST";
      if (OCEANIA.contains(c)) return "OCEANIA";
      if (LATIN_AMERICA.contains(c)) return "LATIN_AMERICA";
      if (AFRICA.contains(c)) return "AFRICA";
    }
    if ("US (NYSE/NASDAQ)".equals(exchange) || "TSX".equals(exchange) || "BMV".equals(exchange))
      return "NORTH_AMERICA";
    if (Set.of("KRX", "TSE", "HKEX", "SSE", "SZSE", "TWSE", "NSE India", "SGX", "IDX", "SET")
        .contains(exchange)) return "ASIA";
    if (Set.of("LSE", "XETRA", "Euronext Paris", "SIX Swiss", "Borsa Italiana", "BME Madrid")
        .contains(exchange)) return "EUROPE";
    return "OTHER";
  }

  static String resolveExchange(String symbol) {
    if (symbol == null || symbol.isBlank()) return "Other";
    int dot = symbol.lastIndexOf('.');
    if (dot < 0 || dot == symbol.length() - 1) return "US (NYSE/NASDAQ)";
    String suffix = symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
    // Class shares like BRK.B or BF.B are US-listed equities
    if (suffix.length() == 1 && (suffix.equals("A") || suffix.equals("B") || suffix.equals("C"))) {
      return "US (NYSE/NASDAQ)";
    }
    return EXCHANGE_SUFFIXES.getOrDefault(suffix, "Other");
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
      String exchange = resolveExchange(symbol);
      result.add(
          new Company(
              rankCounter,
              name,
              symbol,
              marketCap,
              priceUsd,
              country,
              code,
              exchange,
              resolveRegion(code, exchange)));
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
