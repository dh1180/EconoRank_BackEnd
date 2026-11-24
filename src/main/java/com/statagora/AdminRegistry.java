package com.statagora;

import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AdminRegistry implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(AdminRegistry.class);
  private final JdbcTemplate db;
  private final Set<String> adminEmails;

  AdminRegistry(
      JdbcTemplate db, @Value("${statagora.admin.emails:mjdh1936@gmail.com}") String rawEmails) {
    this.db = db;
    this.adminEmails =
        Arrays.stream((rawEmails == null ? "" : rawEmails).split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  boolean isConfiguredAdmin(String email) {
    if (email == null || email.isBlank()) return false;
    return adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
  }

  Set<String> configuredEmails() {
    return adminEmails;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (String email : adminEmails) {
      int updated =
          db.update(
              "UPDATE member SET role='ADMIN' WHERE LOWER(email)=? AND role<>'ADMIN'", email);
      if (updated > 0) {
        log.info("Promoted configured admin account: {}", email);
      }
    }
  }
}
