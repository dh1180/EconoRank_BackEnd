package com.statagora;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class CompanyController {
  private final CompanyMarketCapService service;

  CompanyController(CompanyMarketCapService service) {
    this.service = service;
  }

  record GroupSummary(String code, int count, BigDecimal totalMarketCap) {}

  @GetMapping("/companies")
  Map<String, Object> companies(
      @RequestParam(defaultValue = "250") int limit,
      @RequestParam(required = false) String exchange,
      @RequestParam(required = false) String region) {
    if (limit < 1 || limit > 1000) throw ApiErrors.bad("limit 1–1000");
    var snapshot = service.current();
    if (snapshot == null || snapshot.items().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Live company market cap data is temporarily unavailable");
    }

    Map<String, List<CompanyMarketCapService.Company>> byExchange = new LinkedHashMap<>();
    Map<String, List<CompanyMarketCapService.Company>> byRegion = new LinkedHashMap<>();
    for (var c : snapshot.items()) {
      if (!"Other".equals(c.exchange())) {
        byExchange.computeIfAbsent(c.exchange(), k -> new ArrayList<>()).add(c);
      }
      if (!"OTHER".equals(c.region())) {
        byRegion.computeIfAbsent(c.region(), k -> new ArrayList<>()).add(c);
      }
    }

    List<GroupSummary> exchanges =
        byExchange.entrySet().stream()
            .filter(e -> e.getValue().size() >= 10)
            .map(
                e ->
                    new GroupSummary(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream()
                            .map(CompanyMarketCapService.Company::marketCap)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)))
            .sorted(Comparator.comparing(GroupSummary::totalMarketCap).reversed())
            .toList();

    List<String> regionOrder =
        List.of(
            "NORTH_AMERICA",
            "ASIA",
            "EUROPE",
            "MIDDLE_EAST",
            "OCEANIA",
            "LATIN_AMERICA",
            "AFRICA");
    List<GroupSummary> regions =
        regionOrder.stream()
            .filter(byRegion::containsKey)
            .map(
                r ->
                    new GroupSummary(
                        r,
                        byRegion.get(r).size(),
                        byRegion.get(r).stream()
                            .map(CompanyMarketCapService.Company::marketCap)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)))
            .toList();

    boolean hasExchange =
        exchange != null && !exchange.isBlank() && !exchange.equalsIgnoreCase("ALL");
    boolean hasRegion = region != null && !region.isBlank() && !region.equalsIgnoreCase("ALL");

    var filtered =
        snapshot.items().stream()
            .filter(c -> !hasExchange || c.exchange().equalsIgnoreCase(exchange.trim()))
            .filter(c -> !hasRegion || c.region().equalsIgnoreCase(region.trim()))
            .toList();

    var items = filtered.subList(0, Math.min(limit, filtered.size()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("total", snapshot.items().size());
    body.put("exchangeTotal", filtered.size());
    body.put("exchanges", exchanges);
    body.put("regions", regions);
    body.put("updatedAt", snapshot.updatedAt().toString());
    body.put("refreshIntervalMinutes", CompanyMarketCapService.REFRESH_INTERVAL_MINUTES);
    body.put("source", CompanyMarketCapService.SOURCE_NAME);
    body.put("sourceUrl", CompanyMarketCapService.SOURCE_URL);
    return body;
  }
}
