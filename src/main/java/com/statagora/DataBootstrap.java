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
  private static final String IMF_WEO_URL = "https://data.imf.org/en/datasets/IMF.RES:WEO";
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
      {"gdp", "Nominal GDP (current US$)", "US$", IMF_WEO_URL},
      {"gdppc", "GDP per capita (current US$)", "US$", IMF_WEO_URL},
      {"ppp", "GDP, PPP (current international $)", "international $", IMF_WEO_URL}
    };
    for (var i : indicators) {
      if (db.queryForObject("SELECT COUNT(*) FROM indicator WHERE code=?", Integer.class, i[0])
          == 0) {
        db.update("INSERT INTO indicator VALUES (?,?,?,?)", i[0], i[1], i[2], i[3]);
      } else {
        db.update(
            "UPDATE indicator SET name=?,unit=?,source_url=? WHERE code=?",
            i[1],
            i[2],
            i[3],
            i[0]);
      }
    }
    // Remove any legacy World Bank observations so the database serves IMF WEO data exclusively.
    db.update("DELETE FROM observation WHERE provider<>'imf'");
    // Seed country region metadata and discussion spaces from bundled ISO3 country reference list.
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource("data/world-bank.jsonl").getInputStream(),
                StandardCharsets.UTF_8))) {
      Set<String> seen = new HashSet<>();
      String line;
      while ((line = reader.readLine()) != null) {
        JsonNode row = json.readTree(line);
        String country = row.path("country").asText();
        if (!seen.add(country)) continue;
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
        } else {
          db.update(
              "UPDATE country SET name=?,region=? WHERE code=?",
              row.path("name").asText(),
              row.path("region").asText(),
              country);
        }
      }
    }
  }
}
