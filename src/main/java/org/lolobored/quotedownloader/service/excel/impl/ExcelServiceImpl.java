package org.lolobored.quotedownloader.service.excel.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.excel.ExcelService;
import org.springframework.stereotype.Service;

@Service
public class ExcelServiceImpl implements ExcelService {

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final Pattern CURRENT_PRICES_SHEET = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private static final String[] HEADERS = {
    "Provider", "Name", "Ticker / ID", "Price", "Currency", "Date"
  };

  private static final String[] HISTORY_HEADERS = {
    "Date", "Provider", "Fund Name", "Ticker", "Price", "Currency"
  };

  // History column indices
  private static final int HCOL_DATE = 0;
  private static final int HCOL_PROVIDER = 1;
  private static final int HCOL_NAME = 2;
  private static final int HCOL_TICKER = 3;
  private static final int HCOL_PRICE = 4;
  private static final int HCOL_CURRENCY = 5;

  // Column indices
  private static final int COL_PROVIDER = 0;
  private static final int COL_NAME = 1;
  private static final int COL_TICKER = 2;
  private static final int COL_PRICE = 3;
  private static final int COL_CURRENCY = 4;
  private static final int COL_DATE = 5;

  @Override
  public void writeQuotes(List<Quote> quotes, List<Provider> providers, File outputFile)
      throws IOException {
    List<Quote> sorted = sort(new ArrayList<>(quotes), providers);
    String todayName = LocalDate.now().format(DATE_FORMATTER);

    Workbook workbook;
    Map<String, BigDecimal> previousPrices = new HashMap<>();

    if (outputFile.exists()) {
      try (FileInputStream fis = new FileInputStream(outputFile)) {
        workbook = new XSSFWorkbook(fis);
      }
      // Compare against the most recent sheet that isn't today's
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        if (!workbook.getSheetName(i).equals(todayName)) {
          previousPrices = buildPreviousPrices(workbook.getSheetAt(i));
          break;
        }
      }
    } else {
      workbook = new XSSFWorkbook();
    }

    CellStyle headerStyle = buildHeaderStyle(workbook);
    CellStyle providerStyle = buildProviderStyle(workbook);
    CellStyle priceStyle = buildPriceStyle(workbook, IndexedColors.WHITE);
    CellStyle priceUpStyle = buildPriceStyle(workbook, IndexedColors.LIGHT_GREEN);
    CellStyle priceDownStyle = buildPriceStyle(workbook, IndexedColors.ROSE);

    Sheet todaySheet = workbook.getSheet(todayName);
    if (todaySheet != null) {
      // Sheet already exists — update prices and reapply color highlights in place
      Map<String, Integer> rowByTicker = buildTickerRowMap(todaySheet);
      for (Quote quote : sorted) {
        Integer rowIdx = rowByTicker.get(quote.getTickerOrId());
        if (rowIdx == null) continue;
        Row row = todaySheet.getRow(rowIdx);
        if (row == null) continue;
        Cell priceCell = row.getCell(COL_PRICE);
        if (priceCell == null) priceCell = row.createCell(COL_PRICE);
        priceCell.setCellValue(quote.getPrice().doubleValue());
        priceCell.setCellStyle(
            priceStyleFor(quote, previousPrices, priceStyle, priceUpStyle, priceDownStyle));
        Cell dateCell = row.getCell(COL_DATE);
        if (dateCell == null) dateCell = row.createCell(COL_DATE);
        dateCell.setCellValue(
            quote.getDate() != null ? quote.getDate().format(DATE_FORMATTER) : "");
      }
      applyProviderGroupBorders(todaySheet, sorted);
    } else {
      // Create fresh sheet and move it to the front
      Sheet sheet = workbook.createSheet(todayName);
      workbook.setSheetOrder(todayName, 0);

      // Keep at most 5 current-prices sheets — history sheets (yyyy-MM) are never pruned
      List<Integer> currentPricesIndices = new ArrayList<>();
      for (int idx = 0; idx < workbook.getNumberOfSheets(); idx++) {
        if (CURRENT_PRICES_SHEET.matcher(workbook.getSheetName(idx)).matches()) {
          currentPricesIndices.add(idx);
        }
      }
      while (currentPricesIndices.size() > 4) {
        workbook.removeSheetAt(currentPricesIndices.remove(currentPricesIndices.size() - 1));
      }

      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < HEADERS.length; i++) {
        Cell cell = headerRow.createCell(i);
        cell.setCellValue(HEADERS[i]);
        cell.setCellStyle(headerStyle);
      }

      int rowNum = 1;
      for (Quote quote : sorted) {
        Row row = sheet.createRow(rowNum++);
        row.createCell(COL_PROVIDER)
            .setCellValue(quote.getProviderName() != null ? quote.getProviderName() : "");
        row.createCell(COL_NAME).setCellValue(quote.getName());
        row.createCell(COL_TICKER).setCellValue(quote.getTickerOrId());

        Cell priceCell = row.createCell(COL_PRICE);
        priceCell.setCellValue(quote.getPrice().doubleValue());
        priceCell.setCellStyle(
            priceStyleFor(quote, previousPrices, priceStyle, priceUpStyle, priceDownStyle));

        row.createCell(COL_CURRENCY).setCellValue(quote.getCurrency());
        row.createCell(COL_DATE)
            .setCellValue(quote.getDate() != null ? quote.getDate().format(DATE_FORMATTER) : "");
      }

      // Merge provider cells for consecutive same-provider blocks
      int i = 0;
      while (i < sorted.size()) {
        String name = sorted.get(i).getProviderName();
        int j = i;
        while (j < sorted.size() && name != null && name.equals(sorted.get(j).getProviderName())) {
          j++;
        }
        int firstRow = i + 1;
        int lastRow = j;
        if (lastRow > firstRow) {
          sheet.addMergedRegion(
              new CellRangeAddress(firstRow, lastRow, COL_PROVIDER, COL_PROVIDER));
        }
        for (int k = firstRow; k <= lastRow; k++) {
          sheet.getRow(k).getCell(COL_PROVIDER).setCellStyle(providerStyle);
        }
        i = j;
      }

      applyProviderGroupBorders(sheet, sorted);

      for (int col = 0; col < HEADERS.length; col++) {
        sheet.autoSizeColumn(col);
      }
    }

    writeHistory(workbook, sorted);

    outputFile.getParentFile().mkdirs();
    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
      workbook.write(fos);
    }
    workbook.close();
  }

  private void writeHistory(Workbook workbook, List<Quote> quotes) {
    String monthName = LocalDate.now().format(MONTH_FORMATTER);
    String today = LocalDate.now().format(DATE_FORMATTER);
    CellStyle histHeaderStyle = buildHistoryHeaderStyle(workbook);
    CellStyle priceNumStyle = buildPriceStyle(workbook, IndexedColors.WHITE);

    Sheet sheet = workbook.getSheet(monthName);
    if (sheet == null) {
      sheet = workbook.createSheet(monthName);
      // History sheets live after all current-prices sheets
      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < HISTORY_HEADERS.length; i++) {
        Cell cell = headerRow.createCell(i);
        cell.setCellValue(HISTORY_HEADERS[i]);
        cell.setCellStyle(histHeaderStyle);
      }
      sheet.createFreezePane(0, 1);
    }

    // Build upsert map: "date|ticker" -> row index
    Map<String, Integer> rowByKey = new HashMap<>();
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) continue;
      Cell dateCell = row.getCell(HCOL_DATE);
      Cell tickerCell = row.getCell(HCOL_TICKER);
      if (dateCell != null && tickerCell != null) {
        rowByKey.put(dateCell.getStringCellValue() + "|" + tickerCell.getStringCellValue(), i);
      }
    }

    for (Quote quote : quotes) {
      String key = today + "|" + quote.getTickerOrId();
      Integer existingRow = rowByKey.get(key);
      Row row;
      if (existingRow != null) {
        row = sheet.getRow(existingRow);
      } else {
        row = sheet.createRow(sheet.getLastRowNum() + 1);
        row.createCell(HCOL_DATE).setCellValue(today);
        row.createCell(HCOL_PROVIDER)
            .setCellValue(quote.getProviderName() != null ? quote.getProviderName() : "");
        row.createCell(HCOL_NAME).setCellValue(quote.getName());
        row.createCell(HCOL_TICKER).setCellValue(quote.getTickerOrId());
        row.createCell(HCOL_CURRENCY).setCellValue(quote.getCurrency());
        rowByKey.put(key, row.getRowNum());
      }
      Cell priceCell = row.getCell(HCOL_PRICE);
      if (priceCell == null) priceCell = row.createCell(HCOL_PRICE);
      priceCell.setCellValue(quote.getPrice().doubleValue());
      priceCell.setCellStyle(priceNumStyle);
    }

    for (int col = 0; col < HISTORY_HEADERS.length; col++) {
      sheet.autoSizeColumn(col);
    }
  }

  private CellStyle buildHistoryHeaderStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    font.setColor(IndexedColors.WHITE.getIndex());
    style.setFont(font);
    style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setBorderBottom(BorderStyle.THIN);
    return style;
  }

  private CellStyle priceStyleFor(
      Quote quote, Map<String, BigDecimal> prev, CellStyle normal, CellStyle up, CellStyle down) {
    BigDecimal prevPrice = prev.get(quote.getTickerOrId());
    if (prevPrice == null) return normal;
    int cmp = quote.getPrice().compareTo(prevPrice);
    return cmp > 0 ? up : cmp < 0 ? down : normal;
  }

  private void applyProviderGroupBorders(Sheet sheet, List<Quote> sorted) {
    int i = 0;
    while (i < sorted.size()) {
      String providerName = sorted.get(i).getProviderName();
      int j = i;
      while (j < sorted.size()
          && providerName != null
          && providerName.equals(sorted.get(j).getProviderName())) {
        j++;
      }
      // Add thick bottom border to the last row of this group, but not after the final group
      if (j < sorted.size()) {
        Row row = sheet.getRow(j); // sorted[j-1] is last item → sheet row = j (1-based with header)
        if (row != null) {
          for (int col = 0; col < HEADERS.length; col++) {
            Cell cell = row.getCell(col);
            if (cell == null) cell = row.createCell(col);
            CellUtil.setCellStyleProperty(cell, CellUtil.BORDER_BOTTOM, BorderStyle.MEDIUM);
          }
        }
      }
      i = j;
    }
  }

  private Map<String, Integer> buildTickerRowMap(Sheet sheet) {
    Map<String, Integer> map = new HashMap<>();
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) continue;
      Cell tickerCell = row.getCell(COL_TICKER);
      if (tickerCell != null) map.put(tickerCell.getStringCellValue(), i);
    }
    return map;
  }

  private Map<String, BigDecimal> buildPreviousPrices(Sheet sheet) {
    Map<String, BigDecimal> prices = new HashMap<>();
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) continue;
      Cell tickerCell = row.getCell(COL_TICKER);
      Cell priceCell = row.getCell(COL_PRICE);
      if (tickerCell == null || priceCell == null) continue;
      try {
        prices.put(
            tickerCell.getStringCellValue(), BigDecimal.valueOf(priceCell.getNumericCellValue()));
      } catch (Exception ignored) {
      }
    }
    return prices;
  }

  private List<Quote> sort(List<Quote> quotes, List<Provider> providers) {
    Map<String, Integer> providerIndex = new HashMap<>();
    Map<String, Map<String, Integer>> fundIndex = new HashMap<>();

    for (int i = 0; i < providers.size(); i++) {
      Provider p = providers.get(i);
      providerIndex.put(p.getName(), i);
      Map<String, Integer> fi = new HashMap<>();
      List<Fund> funds = p.getFunds();
      for (int j = 0; j < funds.size(); j++) {
        fi.put(funds.get(j).getFundId(), j);
      }
      fundIndex.put(p.getName(), fi);
    }

    quotes.sort(
        (a, b) -> {
          int pa = providerIndex.getOrDefault(a.getProviderName(), Integer.MAX_VALUE);
          int pb = providerIndex.getOrDefault(b.getProviderName(), Integer.MAX_VALUE);
          if (pa != pb) return Integer.compare(pa, pb);
          Map<String, Integer> fi = fundIndex.get(a.getProviderName());
          int fa =
              fi != null
                  ? fi.getOrDefault(a.getTickerOrId(), Integer.MAX_VALUE)
                  : Integer.MAX_VALUE;
          int fb =
              fi != null
                  ? fi.getOrDefault(b.getTickerOrId(), Integer.MAX_VALUE)
                  : Integer.MAX_VALUE;
          return Integer.compare(fa, fb);
        });

    return quotes;
  }

  private CellStyle buildHeaderStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    font.setColor(IndexedColors.WHITE.getIndex());
    style.setFont(font);
    style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setBorderBottom(BorderStyle.THIN);
    return style;
  }

  private CellStyle buildProviderStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    style.setFont(font);
    style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    return style;
  }

  private CellStyle buildPriceStyle(Workbook workbook, IndexedColors background) {
    CellStyle style = workbook.createCellStyle();
    style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.0000"));
    if (background != IndexedColors.WHITE) {
      style.setFillForegroundColor(background.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    return style;
  }
}
