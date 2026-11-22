package com.statagora;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class EconomicScheduling {}

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class EconomicRefresh implements ApplicationRunner {
  static final String IMF_PAGE = "https://data.imf.org/en/datasets/IMF.RES:WEO";
  private static final Logger log = LoggerFactory.getLogger(EconomicRefresh.class);
  private final EconomicSnapshotStore store;
  private final ObjectMapper json;
  private final JdbcTemplate db;
  private final boolean enabled;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  EconomicRefresh(
      EconomicSnapshotStore store,
      ObjectMapper json,
      JdbcTemplate db,
      @Value("${statagora.refresh.enabled:true}") boolean enabled) {
    this.store = store;
    this.json = json;
    this.db = db;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    try (var input = new ClassPathResource("data/imf-weo.json").getInputStream()) {
      store.apply(json.readValue(input, EconomicSnapshotStore.Snapshot.class));
    }
  }

  @Scheduled(
      initialDelayString = "${statagora.refresh.initial-delay-ms:30000}",
      fixedDelayString = "${statagora.refresh.delay-ms:21600000}")
  public synchronized void refresh() {
    if (!enabled) return;
    String provider = "imf";
    String checked = Instant.now().toString();
    try {
      var snapshot = fetchImf();
      boolean changed = store.apply(snapshot);
      state(
          provider,
          checked,
          "SUCCESS",
          changed ? "Published a new source revision" : "Source unchanged");
    } catch (Exception e) {
      log.warn("Economic refresh failed for {}: {}", provider, e.toString());
      state(
          provider,
          checked,
          "FAILED",
          "Upstream unavailable or validation failed; retained last successful data");
    }
  }

  private byte[] get(String url, int limit) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(90))
            .header("User-Agent", "StatAgora/0.1 (+https://github.com/dh1180/StatAgora_BackEnd)")
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() != 200)
        throw new IllegalStateException("Source HTTP " + response.statusCode());
      byte[] bytes = body.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IllegalStateException("Source exceeds size limit");
      return bytes;
    }
  }

  EconomicSnapshotStore.Snapshot fetchImf() throws Exception {
    String page = new String(get(IMF_PAGE, 5000000), StandardCharsets.UTF_8);
    Matcher link =
        Pattern.compile(
                "href=\"([^\"]*/WEO(?:Apr|Oct)\\d{4}all\\.xlsx)\"", Pattern.CASE_INSENSITIVE)
            .matcher(page);
    if (!link.find()) throw new IllegalStateException("Official WEO workbook link missing");
    URI url = URI.create(IMF_PAGE).resolve(link.group(1));
    if (!url.getScheme().equals("https") || !url.getHost().equals("data.imf.org"))
      throw new IllegalStateException("Unexpected WEO host");
    byte[] bytes = get(url.toString(), 30000000);
    return ImfWorkbookReader.parse(bytes, url.toString(), Instant.now().toString(), sha(bytes));
  }

  private JsonNode worldBank(String path) throws Exception {
    var result = json.readTree(get("https://api.worldbank.org/v2" + path, 15000000));
    if (!result.isArray()
        || result.size() != 2
        || !result.get(1).isArray()
        || result.get(0).path("pages").asInt() != 1)
      throw new IllegalStateException("Invalid or truncated WDI response");
    return result;
  }

  EconomicSnapshotStore.Snapshot fetchWorldBank() throws Exception {
    Map<String, JsonNode> countries = new HashMap<>();
    for (var c : worldBank("/country?format=json&per_page=400").get(1))
      if (!c.path("region").path("id").asText().equals("NA"))
        countries.put(c.path("id").asText(), c);
    var rows = new ArrayList<EconomicSnapshotStore.Value>();
    String published = "";
    for (var metric :
        Map.of("gdp", "NY.GDP.MKTP.CD", "ppp", "NY.GDP.MKTP.PP.CD", "exports", "NE.EXP.GNFS.CD")
            .entrySet()) {
      var result =
          worldBank(
              "/country/all/indicator/"
                  + metric.getValue()
                  + "?format=json&per_page=20000&date=2022:"
                  + Year.now(ZoneOffset.UTC).getValue());
      String updated = result.get(0).path("lastupdated").asText("");
      if (updated.compareTo(published) > 0) published = updated;
      for (var row : result.get(1)) {
        var country = countries.get(row.path("countryiso3code").asText());
        if (country == null
            || !row.path("value").isNumber()
            || row.path("value").decimalValue().signum() < 0) continue;
        rows.add(
            new EconomicSnapshotStore.Value(
                country.path("id").asText(),
                country.path("name").asText(),
                country.path("region").path("value").asText(),
                metric.getKey(),
                row.path("date").asInt(),
                row.path("value").decimalValue(),
                "ACTUAL"));
      }
    }
    rows.sort(
        Comparator.comparing(EconomicSnapshotStore.Value::country)
            .thenComparing(EconomicSnapshotStore.Value::indicator)
            .thenComparingInt(EconomicSnapshotStore.Value::year));
    return new EconomicSnapshotStore.Snapshot(
        "worldbank",
        "World Bank WDI",
        published,
        "WDI " + published,
        "https://data.worldbank.org",
        Instant.now().toString(),
        sha(json.writeValueAsBytes(rows)),
        rows);
  }

  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private void state(String provider, String checked, String status, String message) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM data_sync_state WHERE provider=?", Integer.class, provider)
        == 0)
      db.update(
          "INSERT INTO data_sync_state(provider,checked_at,status,message) VALUES (?,?,?,?)",
          provider,
          checked,
          status,
          message);
    db.update(
        "UPDATE data_sync_state SET checked_at=?,status=?,message=? WHERE provider=?",
        checked,
        status,
        message,
        provider);
    if (status.equals("SUCCESS"))
      db.update("UPDATE data_sync_state SET succeeded_at=? WHERE provider=?", checked, provider);
  }
}
