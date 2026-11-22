package com.statagora;

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

  @GetMapping("/companies")
  Map<String, Object> companies(
      @RequestParam(defaultValue = "500") int limit,
      @RequestParam(required = false) String exchange) {
    if (limit < 1 || limit > 1000) throw ApiErrors.bad("limit 1–1000");
    var snapshot = service.current();
    if (snapshot == null || snapshot.items().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Live company market cap data is temporarily unavailable");
    }
    var filtered =
        (exchange == null || exchange.isBlank() || exchange.equalsIgnoreCase("ALL"))
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
