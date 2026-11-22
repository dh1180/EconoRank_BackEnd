package com.statagora;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Single-instance MVP limiter. Replace with a shared Redis limiter before horizontal scaling. */
@Component
class RequestLimits {
  private final Map<String, Bucket> buckets = new HashMap<>();

  private record Bucket(long expires, int count) {}

  synchronized void check(String key, int max, int seconds) {
    long now = Instant.now().getEpochSecond();
    buckets.entrySet().removeIf(e -> e.getValue().expires <= now);
    if (buckets.size() >= 10000 && !buckets.containsKey(key))
      throw new ApiErrors.Failure(HttpStatus.TOO_MANY_REQUESTS, "Try again later");
    Bucket old = buckets.getOrDefault(key, new Bucket(now + seconds, 0));
    if (old.count >= max)
      throw new ApiErrors.Failure(
          HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
    buckets.put(key, new Bucket(old.expires, old.count + 1));
  }
}
