package org.lolobored.quotedownloader.service.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Fund;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.excel.impl.ExcelServiceImpl;

class ExcelServiceImplTest {

  @TempDir File tempDir;

  private ExcelServiceImpl service;
  private File outputFile;
  private String today;
  private String thisMonth;

  @BeforeEach
  void setUp() {
    service = new ExcelServiceImpl();
    outputFile = new File(tempDir, "quotes.xlsx");
    today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    thisMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
  }

  @Test
  void writeQuotes_createsFile() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    assertThat(outputFile).exists();
  }

  @Test
  void writeQuotes_createsMonthlyHistorySheet() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      assertThat(wb.getSheet(thisMonth)).isNotNull();
    }
  }

  @Test
  void writeQuotes_hasCorrectHeaders() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Row header = wb.getSheet(thisMonth).getRow(0);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Date");
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Provider");
      assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Fund Name");
      assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Ticker");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Price");
      assertThat(header.getCell(5).getStringCellValue()).isEqualTo("Currency");
    }
  }

  @Test
  void writeQuotes_hasCorrectData() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Row row = wb.getSheet(thisMonth).getRow(1);
      assertThat(row.getCell(0).getStringCellValue()).isEqualTo(today);
      assertThat(row.getCell(1).getStringCellValue()).isEqualTo("yahoo");
      assertThat(row.getCell(2).getStringCellValue()).isEqualTo("DBS Group Holdings");
      assertThat(row.getCell(3).getStringCellValue()).isEqualTo("D05.SI");
      assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(58.77);
      assertThat(row.getCell(5).getStringCellValue()).isEqualTo("SGD");
    }
  }

  @Test
  void writeQuotes_noDailySheets() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      long dailySheets =
          java.util.stream.IntStream.range(0, wb.getNumberOfSheets())
              .mapToObj(wb::getSheetName)
              .filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}"))
              .count();
      assertThat(dailySheets).isZero();
    }
  }

  @Test
  void writeQuotes_runTwiceOnSameDay_upsertsPrice() throws Exception {
    service.writeQuotes(quotes(58.77), providers(), outputFile);
    service.writeQuotes(quotes(59.00), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Sheet hist = wb.getSheet(thisMonth);
      assertThat(hist.getLastRowNum()).isEqualTo(1);
      assertThat(hist.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(59.00);
    }
  }

  @Test
  void writeQuotes_multipleDays_appendsRows() throws Exception {
    String yesterday =
        LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    service.writeQuotes(quotes(58.77), providers(), outputFile);
    // Simulate the existing row belonging to yesterday so today creates a new row
    overwriteDateInHistory(yesterday);
    service.writeQuotes(quotes(59.00), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Sheet hist = wb.getSheet(thisMonth);
      assertThat(hist.getLastRowNum()).isEqualTo(2);
    }
  }

  @Test
  void writeQuotes_preservesPreviousMonthSheet() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);
    // Simulate a new month by renaming the current month sheet
    renameSheet(thisMonth, "2000-01");
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      assertThat(wb.getSheet("2000-01")).isNotNull();
      assertThat(wb.getSheet(thisMonth)).isNotNull();
    }
  }

  // --- helpers ---

  private List<Quote> quotes() {
    return quotes(58.77);
  }

  private List<Quote> quotes(double price) {
    Quote q =
        new Quote(
            "DBS Group Holdings", "D05.SI", BigDecimal.valueOf(price), "SGD", LocalDate.now());
    q.setProviderName("yahoo");
    return List.of(q);
  }

  private List<Provider> providers() {
    Fund f = new Fund();
    f.setFundId("D05.SI");
    f.setFundName("DBS Group Holdings");
    f.setCurrency("SGD");
    Provider p = new Provider();
    p.setName("yahoo");
    p.setFunds(List.of(f));
    return List.of(p);
  }

  private void overwriteDateInHistory(String newDate) throws Exception {
    try (FileInputStream fis = new FileInputStream(outputFile);
        Workbook wb = new XSSFWorkbook(fis)) {
      Sheet hist = wb.getSheet(thisMonth);
      if (hist != null) {
        for (int i = 1; i <= hist.getLastRowNum(); i++) {
          Row row = hist.getRow(i);
          if (row != null) {
            Cell dateCell = row.getCell(0);
            if (dateCell != null) dateCell.setCellValue(newDate);
          }
        }
      }
      try (FileOutputStream fos = new FileOutputStream(outputFile)) {
        wb.write(fos);
      }
    }
  }

  private void renameSheet(String from, String to) throws Exception {
    try (FileInputStream fis = new FileInputStream(outputFile);
        Workbook wb = new XSSFWorkbook(fis)) {
      int idx = wb.getSheetIndex(from);
      if (idx >= 0) wb.setSheetName(idx, to);
      try (FileOutputStream fos = new FileOutputStream(outputFile)) {
        wb.write(fos);
      }
    }
  }

  private Workbook open(File file) throws Exception {
    return new XSSFWorkbook(new FileInputStream(file));
  }
}
