package com.statagora;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class ImfWorkbookReaderTest {
  @Test
  void workbookCountrySheetUnitsAndForecastBoundary() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      entry(
          zip,
          "xl/sharedStrings.xml",
          "<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'/>");
      entry(
          zip,
          "xl/workbook.xml",
          "<workbook"
              + " xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet"
              + " name='Countries' r:id='rId2'/></sheets></workbook>");
      entry(
          zip,
          "xl/_rels/workbook.xml.rels",
          "<Relationships><Relationship Id='rId2'"
              + " Target='worksheets/countries.xml'/></Relationships>");
      var sheet = new StringBuilder("<worksheet><sheetData>");
      String[] header = {
        "COUNTRY.ID",
        "COUNTRY",
        "INDICATOR.ID",
        "SCALE",
        "UNIT",
        "LATEST_ACTUAL_ANNUAL_DATA",
        "PUBLICATION_DATE",
        "2024",
        "2026"
      };
      row(sheet, 1, header);
      for (int i = 0; i < 130; i++) {
        String code = "A" + (char) ('A' + i / 26) + (char) ('A' + i % 26);
        int base = 2 + i * 3;
        row(
            sheet,
            base,
            new String[] {
              code,
              "Test economy",
              "NGDPD",
              "Billions",
              "US dollar",
              "FY2024/25",
              "2026-10-13T02:00:00Z",
              "100.125",
              "120.5"
            });
        row(
            sheet,
            base + 1,
            new String[] {
              code,
              "Test economy",
              "NGDPDPC",
              "Units",
              "US dollar",
              "FY2024/25",
              "2026-10-13T02:00:00Z",
              "36000.5",
              "41000.25"
            });
        row(
            sheet,
            base + 2,
            new String[] {
              code,
              "Test economy",
              "PPPGDP",
              "Billions",
              "Purchasing power parity; international dollar",
              "FY2024/25",
              "2026-10-13T02:00:00Z",
              "150.0",
              "175.0"
            });
      }
      sheet.append("</sheetData></worksheet>");
      entry(zip, "xl/worksheets/countries.xml", sheet.toString());
    }
    var snapshot =
        ImfWorkbookReader.parse(
            out.toByteArray(),
            "https://data.imf.org/test.xlsx",
            "2026-11-22T00:00:00Z",
            "a".repeat(64));
    assertThat(snapshot.observations()).hasSize(780);
    assertThat(
            snapshot.observations().stream()
                .filter(v -> v.indicator().equals("gdp") && v.year() == 2024)
                .findFirst()
                .orElseThrow()
                .value())
        .isEqualByComparingTo("100125000000");
    assertThat(
            snapshot.observations().stream()
                .filter(v -> v.indicator().equals("gdppc") && v.year() == 2024)
                .findFirst()
                .orElseThrow()
                .value())
        .isEqualByComparingTo("36000.5");
    assertThat(
            snapshot.observations().stream()
                .filter(v -> v.year() == 2024)
                .map(EconomicSnapshotStore.Value::status))
        .containsOnly("ACTUAL");
    assertThat(
            snapshot.observations().stream()
                .filter(v -> v.year() == 2026)
                .map(EconomicSnapshotStore.Value::status))
        .containsOnly("ESTIMATE_OR_FORECAST");
  }

  private void row(StringBuilder xml, int number, String[] values) {
    xml.append("<row>");
    for (int i = 0; i < values.length; i++)
      xml.append("<c r='")
          .append((char) ('A' + i))
          .append(number)
          .append("' t='inlineStr'><is><t>")
          .append(values[i])
          .append("</t></is></c>");
    xml.append("</row>");
  }

  private void entry(ZipOutputStream zip, String name, String value) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
