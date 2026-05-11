package org.lolobored.quotedownloader.service.excel.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private static final String[] HISTORY_HEADERS = {
    "Date", "Provider", "Fund Name", "Ticker", "Price", "Currency"
  };

  private static final int HCOL_DATE = 0;
  private static final int HCOL_PROVIDER = 1;
  private static final int HCOL_NAME = 2;
  private static final int HCOL_TICKER = 3;
  private static final int HCOL_PRICE = 4;
  private static final int HCOL_CURRENCY = 5;

  @Override
  public void writeQuotes(List<Quote> quotes, List<Provider> providers, File outputFile)
      throws IOException {
    List<Quote> sorted = sort(new ArrayList<>(quotes), providers);

    Workbook workbook;
    if (outputFile.exists()) {
      try (FileInputStream fis = new FileInputStream(outputFile)) {
        workbook = new XSSFWorkbook(fis);
      }
    } else {
      workbook = new XSSFWorkbook();
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
    CellStyle headerStyle = buildHeaderStyle(workbook);
    CellStyle providerStyle = buildProviderStyle(workbook);
    CellStyle priceStyle = buildPriceStyle(workbook, IndexedColors.AUTOMATIC);
    CellStyle priceUpStyle = buildPriceStyle(workbook, IndexedColors.LIGHT_GREEN);
    CellStyle priceDownStyle = buildPriceStyle(workbook, IndexedColors.ROSE);

    Sheet sheet = workbook.getSheet(monthName);
    if (sheet == null) {
      sheet = workbook.createSheet(monthName);
      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < HISTORY_HEADERS.length; i++) {
        Cell cell = headerRow.createCell(i);
        cell.setCellValue(HISTORY_HEADERS[i]);
        cell.setCellStyle(headerStyle);
      }
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HISTORY_HEADERS.length - 1));
    }

    // Scan existing rows: build upsert map and find most recent previous price per ticker.
    // Since new rows are inserted at the top, scanning top-to-bottom means we see the most
    // recent non-today rows first — use putIfAbsent so we keep the newest previous price.
    Map<String, Integer> rowByKey = new HashMap<>();
    Map<String, Double> lastPriceByTicker = new HashMap<>();
    boolean todayRowsExist = false;
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null) continue;
      Cell dateCell = row.getCell(HCOL_DATE);
      Cell tickerCell = row.getCell(HCOL_TICKER);
      Cell priceCell = row.getCell(HCOL_PRICE);
      if (dateCell == null || tickerCell == null || priceCell == null) continue;
      String date = dateCell.getStringCellValue();
      String ticker = tickerCell.getStringCellValue();
      rowByKey.put(date + "|" + ticker, i);
      if (date.equals(today)) {
        todayRowsExist = true;
      } else {
        lastPriceByTicker.putIfAbsent(ticker, priceCell.getNumericCellValue());
      }
    }

    if (!todayRowsExist) {
      // Shift all existing data rows down to make room at the top for today's batch
      int lastRowNum = sheet.getLastRowNum();
      boolean hadExistingRows = lastRowNum >= 1;
      if (hadExistingRows) {
        sheet.shiftRows(1, lastRowNum, quotes.size());
        Map<String, Integer> shifted = new HashMap<>();
        for (Map.Entry<String, Integer> e : rowByKey.entrySet()) {
          shifted.put(e.getKey(), e.getValue() + quotes.size());
        }
        rowByKey = shifted;
      }

      // Insert today's rows at the top (rows 1..quotes.size())
      for (int i = 0; i < quotes.size(); i++) {
        Quote quote = quotes.get(i);
        int rowNum = i + 1;
        Row row = sheet.createRow(rowNum);
        row.createCell(HCOL_DATE).setCellValue(today);
        row.createCell(HCOL_PROVIDER)
            .setCellValue(quote.getProviderName() != null ? quote.getProviderName() : "");
        row.createCell(HCOL_NAME).setCellValue(quote.getName());
        row.createCell(HCOL_TICKER).setCellValue(quote.getTickerOrId());
        row.createCell(HCOL_CURRENCY).setCellValue(quote.getCurrency());
        rowByKey.put(today + "|" + quote.getTickerOrId(), rowNum);
      }

      // Merge, color, and border provider cells for consecutive same-provider rows in the new batch
      int i = 0;
      while (i < quotes.size()) {
        String providerName = quotes.get(i).getProviderName();
        int j = i;
        while (j < quotes.size() && Objects.equals(providerName, quotes.get(j).getProviderName())) {
          j++;
        }
        int firstRow = i + 1;
        int lastRow = j; // inclusive, 1-based
        if (lastRow > firstRow) {
          sheet.addMergedRegion(
              new CellRangeAddress(firstRow, lastRow, HCOL_PROVIDER, HCOL_PROVIDER));
        }
        for (int k = firstRow; k <= lastRow; k++) {
          Cell cell = sheet.getRow(k).getCell(HCOL_PROVIDER);
          if (cell != null) cell.setCellStyle(providerStyle);
        }
        // Thin separator between provider groups (not after the last group)
        if (j < quotes.size()) {
          applyBottomBorder(sheet, lastRow, BorderStyle.THIN);
        }
        i = j;
      }

      // Medium separator between today's batch and the previous day's rows
      if (hadExistingRows) {
        applyBottomBorder(sheet, quotes.size(), BorderStyle.MEDIUM);
      }
    }

    // Update prices with colour highlighting
    for (Quote quote : quotes) {
      String key = today + "|" + quote.getTickerOrId();
      Integer rowIndex = rowByKey.get(key);
      if (rowIndex == null) continue;
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      double newPrice = quote.getPrice().doubleValue();
      Cell priceCell = row.getCell(HCOL_PRICE);
      if (priceCell == null) priceCell = row.createCell(HCOL_PRICE);
      priceCell.setCellValue(newPrice);

      Double prev = lastPriceByTicker.get(quote.getTickerOrId());
      if (prev == null) {
        priceCell.setCellStyle(priceStyle);
      } else if (newPrice > prev) {
        priceCell.setCellStyle(priceUpStyle);
      } else if (newPrice < prev) {
        priceCell.setCellStyle(priceDownStyle);
      } else {
        priceCell.setCellStyle(priceStyle);
      }
    }

    for (int col = 0; col < HISTORY_HEADERS.length; col++) {
      sheet.autoSizeColumn(col);
    }
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

  private void applyBottomBorder(Sheet sheet, int rowIndex, BorderStyle style) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) return;
    for (int col = 0; col < HISTORY_HEADERS.length; col++) {
      Cell cell = row.getCell(col);
      if (cell == null) cell = row.createCell(col);
      CellUtil.setCellStyleProperty(cell, CellUtil.BORDER_BOTTOM, style);
    }
  }

  private CellStyle buildHeaderStyle(Workbook workbook) {
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
    if (background != IndexedColors.AUTOMATIC) {
      style.setFillForegroundColor(background.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    return style;
  }
}
