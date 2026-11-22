package com.statagora;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EconomicSnapshotStore {
  record Value(
      String country,
      String name,
      String region,
      String indicator,
      int year,
      BigDecimal value,
      String status) {}

  record Snapshot(
      String provider,
      String source,
      String publishedAt,
      String edition,
      String sourceUrl,
      String retrievedAt,
      String checksum,
      List<Value> observations) {}

  private final JdbcTemplate db;

  EconomicSnapshotStore(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public boolean apply(Snapshot snapshot) {
    var canonical =
        snapshot.observations().stream()
            .map(
                v ->
                    new Value(
                        switch (v.country()) {
                          case "KOS" -> "XKX";
                          case "WBG" -> "PSE";
                          default -> v.country();
                        },
                        v.name(),
                        v.region(),
                        v.indicator(),
                        v.year(),
                        v.value(),
                        v.status()))
            .toList();
    snapshot =
        new Snapshot(
            snapshot.provider(),
            snapshot.source(),
            snapshot.publishedAt(),
            snapshot.edition(),
            snapshot.sourceUrl(),
            snapshot.retrievedAt(),
            snapshot.checksum(),
            canonical);
    if (!snapshot.provider().equals("imf")
        || snapshot.observations().size() < 100
        || !snapshot.checksum().matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Incomplete economic release");
    Set<String> keys = new HashSet<>();
    for (var v : snapshot.observations()) {
      if (!v.country().matches("[A-Z]{3}")
          || !Set.of("gdp", "gdppc", "ppp").contains(v.indicator())
          || v.year() < 1960
          || v.year() > 2100
          || v.value() == null
          || v.value().signum() < 0
          || !Set.of("ACTUAL", "ESTIMATE_OR_FORECAST").contains(v.status())
          || !keys.add(v.country() + v.indicator() + v.year()))
        throw new IllegalArgumentException("Invalid or duplicated observation");
    }
    for (String metric : List.of("gdp", "gdppc", "ppp")) {
      long coverage =
          snapshot.observations().stream()
              .filter(v -> v.indicator().equals(metric))
              .map(Value::country)
              .distinct()
              .count();
      if (coverage < 100)
        throw new IllegalArgumentException("Insufficient country coverage for " + metric);
    }
    String id = snapshot.provider() + "-" + snapshot.checksum().substring(0, 32) + "-v3";
    if (db.queryForObject("SELECT COUNT(*) FROM dataset_release WHERE id=?", Integer.class, id) > 0)
      return false;
    // A failed download/validation never reaches this transaction; all source rows publish
    // together.
    db.update(
        "INSERT INTO dataset_release(id,retrieved_at,source,published_at,edition,source_url) VALUES"
            + " (?,?,?,?,?,?)",
        id,
        snapshot.retrievedAt(),
        snapshot.source(),
        snapshot.publishedAt(),
        snapshot.edition(),
        snapshot.sourceUrl());
    for (var v : snapshot.observations()) {
      if (db.queryForObject("SELECT COUNT(*) FROM country WHERE code=?", Integer.class, v.country())
          == 0) {
        db.update(
            "INSERT INTO country VALUES (?,?,?)",
            v.country(),
            v.name(),
            v.region() == null ? "Other economies" : v.region());
        db.update(
            "INSERT INTO discussion_space VALUES (?,?,?)",
            "country-" + v.country(),
            "COUNTRY",
            v.country());
      }
    }
    db.update("DELETE FROM observation WHERE provider=?", snapshot.provider());
    String provider=snapshot.provider();
    db.batchUpdate(
        "INSERT INTO"
            + " observation(country_code,indicator_code,reference_year,amount,release_id,provider,observation_status)"
            + " VALUES (?,?,?,?,?,?,?)",
        snapshot.observations(),
        500,
        (ps, v) -> {
          ps.setString(1, v.country());
          ps.setString(2, v.indicator());
          ps.setInt(3, v.year());
          ps.setBigDecimal(4, v.value());
          ps.setString(5, id);
          ps.setString(6, provider);
          ps.setString(7, v.status());
        });
    return true;
  }
}
