package com.statagora;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class EconomyController {
  private final JdbcTemplate db;

  EconomyController(JdbcTemplate db) {
    this.db = db;
  }

  private static final String SELECT =
      """
SELECT c.code,c.name,c.region,o.indicator_code AS indicator,o.amount AS "value",o.reference_year AS "year",
i.unit,o.provider AS provider,o.observation_status AS "status",d.source AS source,
CASE WHEN d.source_url='' THEN i.source_url ELSE d.source_url END AS "sourceUrl",
d.retrieved_at AS "retrievedAt",d.published_at AS "publishedAt",d.edition AS edition
FROM observation o JOIN country c ON c.code=o.country_code JOIN indicator i ON i.code=o.indicator_code
JOIN dataset_release d ON d.id=o.release_id
""";

  @GetMapping("/health")
  Map<String, String> health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "ok");
  }

  @GetMapping("/indicators")
  List<Map<String, Object>> indicators() {
    return db.queryForList(
        "SELECT code,name,unit,source_url AS \"sourceUrl\" FROM indicator ORDER BY code");
  }

  @GetMapping("/years")
  List<Integer> years(
      @RequestParam(defaultValue = "gdp") String indicator,
      @RequestParam(defaultValue = "auto") String source) {
    metric(indicator);
    return db.queryForList(
        "SELECT DISTINCT reference_year FROM observation WHERE indicator_code=? AND provider=?"
            + " ORDER BY reference_year DESC",
        Integer.class,
        indicator,
        provider(indicator, source));
  }

  @GetMapping("/countries")
  List<Map<String, Object>> countries() {
    return db.queryForList("SELECT * FROM country WHERE code NOT IN ('KOS','WBG') ORDER BY name");
  }

  @GetMapping("/rankings")
  Map<String, Object> rankings(
      @RequestParam(defaultValue = "gdp") String indicator,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "auto") String source,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    metric(indicator);
    pagination(page, size);
    String provider = provider(indicator, source);
    int selected = year == null ? latestYear(indicator, provider) : year;
    year(selected);
    var rows =
        db.queryForList(
            "SELECT RANK() OVER (ORDER BY t.\"value\" DESC) AS \"rank\",t.* FROM ("
                + SELECT
                + " WHERE o.indicator_code=? AND o.reference_year=? AND o.provider=?) t ORDER BY"
                + " t.\"value\" DESC,t.code LIMIT ? OFFSET ?",
            indicator,
            selected,
            provider,
            size,
            page * size);
    int total =
        db.queryForObject(
            "SELECT COUNT(*) FROM observation WHERE indicator_code=? AND reference_year=? AND"
                + " provider=?",
            Integer.class,
            indicator,
            selected,
            provider);
    return Map.of(
        "items",
        rows,
        "page",
        page,
        "size",
        size,
        "total",
        total,
        "indicator",
        indicator,
        "year",
        selected,
        "source",
        provider.equals("imf") ? "IMF WEO" : "World Bank WDI",
        "provider",
        provider);
  }

  @GetMapping("/countries/{code}")
  Map<String, Object> country(
      @PathVariable String code, @RequestParam(defaultValue = "auto") String source) {
    var countries = db.queryForList("SELECT * FROM country WHERE code=?", code);
    if (countries.isEmpty()) throw ApiErrors.missing();
    return Map.of(
        "country", countries.get(0), "observations", observations(List.of(code), null, source));
  }

  @GetMapping("/compare")
  Map<String, Object> compare(
      @RequestParam String countries,
      @RequestParam(required = false) Integer year,
      @RequestParam(defaultValue = "auto") String source) {
    int selected = year == null ? latestYear("gdp", provider("gdp", source)) : year;
    year(selected);
    var codes = Arrays.stream(countries.split(",", -1)).distinct().toList();
    if (codes.size() < 2
        || codes.size() > 5
        || codes.stream().anyMatch(c -> !c.matches("[A-Z]{3}")))
      throw ApiErrors.bad("Choose 2 to 5 ISO3 country codes");
    for (String code : codes)
      if (db.queryForObject("SELECT COUNT(*) FROM country WHERE code=?", Integer.class, code) == 0)
        throw ApiErrors.missing();
    return Map.of(
        "countries", codes, "year", selected, "items", observations(codes, selected, source));
  }

  @GetMapping("/data/status")
  Map<String, Object> status() {
    return Map.of(
        "refreshIntervalHours",
        6,
        "sources",
        db.queryForList(
            "SELECT provider,checked_at AS \"checkedAt\",succeeded_at AS"
                + " \"succeededAt\",status,message FROM data_sync_state"),
        "releases",
        db.queryForList(
            "SELECT DISTINCT d.source,d.edition,d.published_at AS \"publishedAt\",d.retrieved_at AS"
                + " \"retrievedAt\",o.provider FROM dataset_release d JOIN observation o ON"
                + " o.release_id=d.id ORDER BY d.retrieved_at DESC"));
  }

  private List<Map<String, Object>> observations(List<String> codes, Integer year, String source) {
    String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
    var result = new ArrayList<Map<String, Object>>();
    for (String metric : List.of("gdp", "ppp", "exports")) {
      var args = new ArrayList<Object>(codes);
      args.add(metric);
      args.add(provider(metric, metric.equals("exports") ? "worldbank" : source));
      if (year != null) args.add(year);
      result.addAll(
          db.queryForList(
              SELECT
                  + " WHERE c.code IN ("
                  + placeholders
                  + ") AND o.indicator_code=? AND o.provider=?"
                  + (year == null ? "" : " AND o.reference_year=?")
                  + " ORDER BY o.reference_year,c.code",
              args.toArray()));
    }
    return result;
  }

  private String provider(String indicator, String source) {
    if (!Set.of("auto", "imf", "worldbank").contains(source)) throw ApiErrors.bad("Unknown source");
    if (indicator.equals("exports")) {
      if (source.equals("imf"))
        throw ApiErrors.bad("IMF WEO does not provide this export level series");
      return "worldbank";
    }
    if (!source.equals("auto")) return source;
    return db.queryForObject(
                "SELECT COUNT(*) FROM observation WHERE provider='imf' AND indicator_code=?",
                Integer.class,
                indicator)
            > 0
        ? "imf"
        : "worldbank";
  }

  private int latestYear(String metric, String provider) {
    Integer year =
        db.queryForObject(
            "SELECT MAX(reference_year) FROM observation WHERE indicator_code=? AND provider=? AND"
                + " reference_year<=?",
            Integer.class,
            metric,
            provider,
            Year.now(ZoneOffset.UTC).getValue());
    if (year == null) throw ApiErrors.missing();
    return year;
  }

  static void pagination(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw ApiErrors.bad("page 0–10000; size 1–100");
  }

  private void metric(String code) {
    if (!Set.of("gdp", "ppp", "exports").contains(code)) throw ApiErrors.bad("Unknown indicator");
  }

  private void year(int year) {
    if (year < 1960 || year > 2100) throw ApiErrors.bad("Invalid year");
  }
}
