package com.statagora;

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
  List<Integer> years() {
    return db.queryForList(
        "SELECT DISTINCT reference_year FROM observation ORDER BY reference_year DESC",
        Integer.class);
  }

  @GetMapping("/countries")
  List<Map<String, Object>> countries() {
    return db.queryForList("SELECT * FROM country ORDER BY name");
  }

  @GetMapping("/rankings")
  Map<String, Object> rankings(
      @RequestParam(defaultValue = "gdp") String indicator,
      @RequestParam(defaultValue = "2024") int year,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    metric(indicator);
    year(year);
    pagination(page, size);
    var rows =
        db.queryForList(
            """
SELECT RANK() OVER (ORDER BY o.amount DESC) AS "rank", c.code, c.name, c.region,
o.amount AS "value", o.reference_year AS "year", i.unit, i.source_url AS "sourceUrl", d.retrieved_at AS "retrievedAt"
FROM observation o JOIN country c ON c.code=o.country_code JOIN indicator i ON i.code=o.indicator_code
JOIN dataset_release d ON d.id=o.release_id WHERE o.indicator_code=? AND o.reference_year=?
ORDER BY o.amount DESC,c.code LIMIT ? OFFSET ?
""",
            indicator,
            year,
            size,
            page * size);
    int total =
        db.queryForObject(
            "SELECT COUNT(*) FROM observation WHERE indicator_code=? AND reference_year=?",
            Integer.class,
            indicator,
            year);
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
        year,
        "source",
        "World Bank WDI");
  }

  @GetMapping("/countries/{code}")
  Map<String, Object> country(@PathVariable String code) {
    var countries = db.queryForList("SELECT * FROM country WHERE code=?", code);
    if (countries.isEmpty()) throw ApiErrors.missing();
    return Map.of("country", countries.get(0), "observations", observations(List.of(code), null));
  }

  @GetMapping("/compare")
  Map<String, Object> compare(
      @RequestParam String countries, @RequestParam(defaultValue = "2024") int year) {
    year(year);
    var codes = Arrays.stream(countries.split(",", -1)).distinct().toList();
    if (codes.size() < 2
        || codes.size() > 5
        || codes.stream().anyMatch(c -> !c.matches("[A-Z]{3}")))
      throw ApiErrors.bad("Choose 2 to 5 ISO3 country codes");
    for (String code : codes)
      if (db.queryForObject("SELECT COUNT(*) FROM country WHERE code=?", Integer.class, code) == 0)
        throw ApiErrors.missing();
    return Map.of("countries", codes, "year", year, "items", observations(codes, year));
  }

  private List<Map<String, Object>> observations(List<String> codes, Integer year) {
    String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
    var args = new ArrayList<Object>(codes);
    if (year != null) args.add(year);
    return db.queryForList(
        """
SELECT c.code,c.name,o.indicator_code AS indicator,o.reference_year AS "year",o.amount AS "value",
i.unit,i.source_url AS "sourceUrl",d.retrieved_at AS "retrievedAt" FROM observation o
JOIN country c ON c.code=o.country_code JOIN indicator i ON i.code=o.indicator_code
JOIN dataset_release d ON d.id=o.release_id WHERE c.code IN (
"""
            + placeholders
            + ")"
            + (year == null ? "" : " AND o.reference_year=?")
            + " ORDER BY o.reference_year,c.code,o.indicator_code",
        args.toArray());
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
