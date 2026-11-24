package com.statagora;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CompanyMarketCapServiceTest {
  @Test
  void parsesQuotedCsvRowsAndResolvesCountryCodes() {
    String csv =
        """
        "Rank","Name","Symbol","marketcap","price (USD)","country"
        "1","Apple","AAPL","4978144444416","342.56","United States"
        "2","Alphabet, Inc. (Google)","GOOG","3245615874048","264","United States"
        "3","Samsung Electronics","005930.KS","420000000000","62.10","South Korea"
        "4","KT&amp;G (Korea Tobacco)","033780.KS","12000000000","80.00","South Korea"
        "5","Invalid Zero","ZERO","0","1.00","Japan"
        """;
    var items =
        CompanyMarketCapService.parseCsv(
            csv, Map.of("united states", "USA", "south korea", "KOR", "japan", "JPN"));
    assertThat(items).hasSize(4);
    assertThat(items.get(3).name()).isEqualTo("KT&G (Korea Tobacco)");
    assertThat(items.get(0).rank()).isEqualTo(1);
    assertThat(items.get(0).symbol()).isEqualTo("AAPL");
    assertThat(items.get(0).countryCode()).isEqualTo("USA");
    assertThat(items.get(0).exchange()).isEqualTo("US (NYSE/NASDAQ)");
    assertThat(items.get(0).region()).isEqualTo("NORTH_AMERICA");
    assertThat(items.get(1).name()).isEqualTo("Alphabet, Inc. (Google)");
    assertThat(items.get(2).countryCode()).isEqualTo("KOR");
    assertThat(items.get(2).exchange()).isEqualTo("KRX");
    assertThat(items.get(2).region()).isEqualTo("ASIA");
    assertThat(items.get(2).priceUsd()).isEqualByComparingTo("62.10");
    assertThat(CompanyMarketCapService.resolveExchange("7203.T")).isEqualTo("TSE");
    assertThat(CompanyMarketCapService.resolveExchange("BRK.B")).isEqualTo("US (NYSE/NASDAQ)");
    assertThat(CompanyMarketCapService.resolveRegion("DEU", "XETRA")).isEqualTo("EUROPE");
  }
}
