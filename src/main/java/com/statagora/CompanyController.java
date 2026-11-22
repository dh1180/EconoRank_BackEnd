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

  record ExchangeSummary(String code, int count, BigDecimal totalMarketCap) {}

  @GetMapping("/companies")
  Map<String, Object> companies(
      @RequestParam(defaultValue = "250") int limit,
      @RequestParam(required = false) String exchange) {
    if (limit < 1 || limit > 1000) throw ApiErrors.bad("limit 1–1000");
    var snapshot = service.current();
    if (snapshot == null || snapshot.items().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Live company market cap data is temporarily unavailable");
    }

    Map<String, List<CompanyMarketCapService.Company>> byExchange = new LinkedHashMap<>();
    for (var c : snapshot.items()) {
      if ("Other".equals(c.exchange())) continue;
      byExchange.computeIfAbsent(c.exchange(), k -> new ArrayList<>()).add(c);
    }
    List<ExchangeSummary> exchanges =
        byExchange.entrySet().stream()
            .filter(e -> e.getValue().size() >= 10)
            .map(
                e ->
                    new ExchangeSummary(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream()
                            .map(CompanyMarketCapService.Company::marketCap)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)))
            .sorted(Comparator.comparing(ExchangeSummary::totalMarketCap).reversed())
            .toList();

    boolean isAll = exchange == null || exchange.isBlank() || exchange.equalsIgnoreCase("ALL");
    var filtered =
        isAll
            ? snapshot.items()
            : snapshot.items().stream()
                .filter(c -> c.exchange().equalsIgnoreCase(exchange.trim()))
                .toList();
    var items = filtered.subList(0, Math.min(limit, filtered.size()));
    return Map.of(
        "items",
        items,
        "total",
        snapshot.items().size(),
        "exchangeTotal",
        filtered.size(),
        "exchanges",
        exchanges,
        "updatedAt",
        snapshot.updatedAt().toString(),
        "refreshIntervalMinutes",
        CompanyMarketCapService.REFRESH_INTERVAL_MINUTES,
        "source",
        CompanyMarketCapService.SOURCE_NAME,
        "sourceUrl",
        CompanyMarketCapService.SOURCE_URL);
  }
}
