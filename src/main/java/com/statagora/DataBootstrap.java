package com.statagora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.core.annotation.Order(0)
class DataBootstrap implements ApplicationRunner {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  DataBootstrap(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    String[][] indicators = {
      {"gdp", "GDP (current US$)", "US$", "NY.GDP.MKTP.CD"},
      {"ppp", "GDP, PPP (current international $)", "international $", "NY.GDP.MKTP.PP.CD"},
      {"exports", "Exports of goods and services (current US$)", "US$", "NE.EXP.GNFS.CD"}
    };
    for (var i : indicators)
      if (db.queryForObject("SELECT COUNT(*) FROM indicator WHERE code=?", Integer.class, i[0])
          == 0)
        db.update(
            "INSERT INTO indicator VALUES (?,?,?,?)",
            i[0],
            i[1],
            i[2],
            "https://data.worldbank.org/indicator/" + i[3]);
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource("data/world-bank.jsonl").getInputStream(),
                StandardCharsets.UTF_8))) {
      List<JsonNode> rows = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) rows.add(json.readTree(line));
      if (rows.size() < 100) throw new IllegalStateException("Incomplete WDI snapshot");
      String release = rows.get(0).path("retrievedAt").asText();
      if (rows.stream().anyMatch(r -> !r.path("retrievedAt").asText().equals(release)))
        throw new IllegalStateException("Mixed dataset releases");
      if (db.queryForObject(
              "SELECT COUNT(*) FROM dataset_release WHERE id=?", Integer.class, release)
          > 0) return;
      db.update(
          "INSERT INTO dataset_release(id,retrieved_at,source) VALUES (?,?,?)",
          release,
          release,
          "World Bank WDI");
      Set<String> partitions = new HashSet<>();
      for (var row : rows) {
        String key = row.path("indicator").asText() + ":" + row.path("year").asInt();
        if (partitions.add(key))
          db.update(
              "DELETE FROM observation WHERE provider='worldbank' AND indicator_code=? AND"
                  + " reference_year=?",
              row.path("indicator").asText(),
              row.path("year").asInt());
      }
      for (var row : rows) {
        String country = row.path("country").asText();
        if (db.queryForObject("SELECT COUNT(*) FROM country WHERE code=?", Integer.class, country)
            == 0) {
          db.update(
              "INSERT INTO country VALUES (?,?,?)",
              country,
              row.path("name").asText(),
              row.path("region").asText());
          db.update(
              "INSERT INTO discussion_space VALUES (?,?,?)",
              "country-" + country,
              "COUNTRY",
              country);
        }
        db.update(
            "UPDATE country SET name=?,region=? WHERE code=?",
            row.path("name").asText(),
            row.path("region").asText(),
            country);
        db.update(
            "INSERT INTO observation(country_code,indicator_code,reference_year,amount,release_id)"
                + " VALUES (?,?,?,?,?)",
            country,
            row.path("indicator").asText(),
            row.path("year").asInt(),
            row.path("value").decimalValue(),
            release);
      }
    }
  }
}
