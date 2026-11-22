package com.statagora;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import javax.xml.stream.*;

/** Streams the official WEO Countries worksheet. External entities/DTDs are disabled. */
class ImfWorkbookReader {
  private static XMLStreamReader xml(InputStream input) throws XMLStreamException {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    return factory.createXMLStreamReader(input);
  }

  static EconomicSnapshotStore.Snapshot parse(
      byte[] bytes, String url, String retrieved, String checksum) throws Exception {
    File temp = File.createTempFile("statagora-weo-", ".xlsx");
    try {
      java.nio.file.Files.write(temp.toPath(), bytes);
      try (var zip = new ZipFile(temp)) {
        List<String> strings = new ArrayList<>();
        try (var input = zip.getInputStream(zip.getEntry("xl/sharedStrings.xml"))) {
          var reader = xml(input);
          StringBuilder value = new StringBuilder();
          while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("si"))
              value = new StringBuilder();
            else if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("t"))
              value.append(reader.getElementText());
            else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("si"))
              strings.add(value.toString());
          }
          reader.close();
        }
        String relation = null;
        try (var input = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
          var reader = xml(input);
          while (reader.hasNext())
            if (reader.next() == XMLStreamConstants.START_ELEMENT
                && reader.getLocalName().equals("sheet")
                && "Countries".equals(reader.getAttributeValue(null, "name")))
              relation =
                  reader.getAttributeValue(
                      "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
          reader.close();
        }
        String target = null;
        try (var input = zip.getInputStream(zip.getEntry("xl/_rels/workbook.xml.rels"))) {
          var reader = xml(input);
          while (reader.hasNext())
            if (reader.next() == XMLStreamConstants.START_ELEMENT
                && reader.getLocalName().equals("Relationship")
                && Objects.equals(relation, reader.getAttributeValue(null, "Id")))
              target = reader.getAttributeValue(null, "Target");
          reader.close();
        }
        if (target == null) throw new IOException("IMF Countries worksheet not found");
        target = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        var values = new ArrayList<EconomicSnapshotStore.Value>();
        var publications = new HashSet<String>();
        Map<String, Integer> header = new HashMap<>();
        try (var input = zip.getInputStream(zip.getEntry(target))) {
          rows(
              input,
              strings,
              row -> {
                if (header.isEmpty()) {
                  row.forEach((col, label) -> header.put(label, col));
                  return;
                }
                String metric =
                    Map.of("NGDPD", "gdp", "NGDPDPC", "gdppc", "PPPGDP", "ppp")
                        .get(cell(row, header, "INDICATOR.ID"));
                if (metric == null) return;
                String expectedScale = metric.equals("gdppc") ? "Units" : "Billions";
                if (!cell(row, header, "SCALE").equals(expectedScale))
                  throw new IllegalArgumentException("Unexpected IMF scale");
                if (!Set.of("US dollar", "Purchasing power parity; international dollar")
                        .contains(cell(row, header, "UNIT"))
                    && (metric.equals("gdp") || metric.equals("gdppc")))
                  throw new IllegalArgumentException("Unexpected IMF unit");
                BigDecimal multiplier =
                    expectedScale.equals("Units")
                        ? BigDecimal.ONE
                        : BigDecimal.valueOf(1000000000L);
                var actualMatch =
                    java.util.regex.Pattern.compile("(?:19|20)\\d{2}")
                        .matcher(cell(row, header, "LATEST_ACTUAL_ANNUAL_DATA"));
                int actual = actualMatch.find() ? Integer.parseInt(actualMatch.group()) : 0;
                publications.add(cell(row, header, "PUBLICATION_DATE"));
                for (var entry : header.entrySet())
                  if (entry.getKey().matches("20[2-9][0-9]")
                      && Integer.parseInt(entry.getKey()) >= 2022) {
                    String text = row.getOrDefault(entry.getValue(), "");
                    if (text.isBlank() || text.equals("n/a") || text.equals("--")) continue;
                    BigDecimal amount;
                    try {
                      amount = new BigDecimal(text).multiply(multiplier);
                    } catch (NumberFormatException e) {
                      continue;
                    }
                    if (amount.signum() < 0) continue;
                    int year = Integer.parseInt(entry.getKey());
                    values.add(
                        new EconomicSnapshotStore.Value(
                            cell(row, header, "COUNTRY.ID"),
                            cell(row, header, "COUNTRY"),
                            null,
                            metric,
                            year,
                            amount,
                            year <= actual ? "ACTUAL" : "ESTIMATE_OR_FORECAST"));
                  }
              });
        }
        if (publications.size() != 1 || values.size() < 500)
          throw new IOException("Incomplete or mixed WEO release");
        String publication = publications.iterator().next();
        java.time.Instant.parse(publication);
        return new EconomicSnapshotStore.Snapshot(
            "imf",
            "IMF WEO",
            publication,
            "WEO " + publication.substring(0, 7),
            url,
            retrieved,
            checksum,
            values);
      }
    } finally {
      java.nio.file.Files.deleteIfExists(temp.toPath());
    }
  }

  private static String cell(Map<Integer, String> row, Map<String, Integer> header, String name) {
    Integer column = header.get(name);
    if (column == null) throw new IllegalArgumentException("Missing IMF field " + name);
    return row.getOrDefault(column, "");
  }

  private static void rows(
      InputStream input, List<String> strings, Consumer<Map<Integer, String>> consumer)
      throws Exception {
    var reader = xml(input);
    Map<Integer, String> row = new HashMap<>();
    String type = "";
    int column = 0;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT)
        switch (reader.getLocalName()) {
          case "row" -> row = new HashMap<>();
          case "c" -> {
            type = reader.getAttributeValue(null, "t");
            String ref = reader.getAttributeValue(null, "r");
            column = 0;
            for (char c : ref.toCharArray()) {
              if (c < 'A' || c > 'Z') break;
              column = column * 26 + c - 'A' + 1;
            }
            column--;
          }
          case "v" -> {
            String value = reader.getElementText();
            row.put(column, "s".equals(type) ? strings.get(Integer.parseInt(value)) : value);
          }
          case "t" -> {
            if ("inlineStr".equals(type)) row.put(column, reader.getElementText());
          }
        }
      else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("row"))
        consumer.accept(row);
    }
    reader.close();
  }
}
