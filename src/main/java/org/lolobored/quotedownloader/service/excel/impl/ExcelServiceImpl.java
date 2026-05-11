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
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
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
    CellStyle priceStyle = buildPriceStyle(workbook);

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
      priceCell.setCellStyle(priceStyle);
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

  private CellStyle buildPriceStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.0000"));
    return style;
  }
}
