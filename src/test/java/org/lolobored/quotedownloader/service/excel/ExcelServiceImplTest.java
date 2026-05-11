package org.lolobored.quotedownloader.service.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
  void writeQuotes_createsFileWithTodaySheet() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    assertThat(outputFile).exists();
    try (Workbook wb = open(outputFile)) {
      assertThat(wb.getSheet(today)).isNotNull();
    }
  }

  @Test
  void writeQuotes_todaySheet_hasCorrectHeaders() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Row header = wb.getSheet(today).getRow(0);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Provider");
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Name");
      assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Ticker / ID");
      assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Price");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Currency");
    }
  }

  @Test
  void writeQuotes_todaySheet_hasCorrectData() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Row row = wb.getSheet(today).getRow(1);
      assertThat(row.getCell(1).getStringCellValue()).isEqualTo("DBS Group Holdings");
      assertThat(row.getCell(2).getStringCellValue()).isEqualTo("D05.SI");
      assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(58.77);
      assertThat(row.getCell(4).getStringCellValue()).isEqualTo("SGD");
    }
  }

  @Test
  void writeQuotes_createsHistorySheet() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      assertThat(wb.getSheet(thisMonth)).isNotNull();
    }
  }

  @Test
  void writeQuotes_historySheet_hasCorrectHeaders() throws Exception {
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
  void writeQuotes_historySheet_hasCorrectData() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Sheet hist = wb.getSheet(thisMonth);
      Row row = hist.getRow(1);
      assertThat(row.getCell(0).getStringCellValue()).isEqualTo(today);
      assertThat(row.getCell(3).getStringCellValue()).isEqualTo("D05.SI");
      assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(58.77);
    }
  }

  @Test
  void writeQuotes_runTwiceOnSameDay_upsertsInsteadOfDuplicating() throws Exception {
    service.writeQuotes(quotes(58.77), providers(), outputFile);
    service.writeQuotes(quotes(59.00), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      Sheet hist = wb.getSheet(thisMonth);
      // Only 1 data row (header + 1)
      assertThat(hist.getLastRowNum()).isEqualTo(1);
      assertThat(hist.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(59.00);
    }
  }

  @Test
  void writeQuotes_todaySheetAppearsFirst() throws Exception {
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      assertThat(wb.getSheetName(0)).isEqualTo(today);
    }
  }

  @Test
  void writeQuotes_rotatesOldCurrentPriceSheets() throws Exception {
    // Write 5 more sheets by setting their names manually isn't easy,
    // so we simulate 5 prior days + today = 6 total → expect only 5 current-price sheets
    for (int i = 5; i >= 1; i--) {
      writeWithDate(LocalDate.now().minusDays(i));
    }
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
      long currentPriceSheets =
          java.util.stream.IntStream.range(0, wb.getNumberOfSheets())
              .mapToObj(wb::getSheetName)
              .filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}"))
              .count();
      assertThat(currentPriceSheets).isLessThanOrEqualTo(5);
    }
  }

  @Test
  void writeQuotes_historySheetNeverRotated() throws Exception {
    for (int i = 5; i >= 1; i--) {
      writeWithDate(LocalDate.now().minusDays(i));
    }
    service.writeQuotes(quotes(), providers(), outputFile);

    try (Workbook wb = open(outputFile)) {
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

  private void writeWithDate(LocalDate date) throws Exception {
    Quote q = new Quote("DBS Group Holdings", "D05.SI", BigDecimal.valueOf(58.00), "SGD", date);
    q.setProviderName("yahoo");
    // Use reflection to call writeQuotes with a fixed date — instead just write normally
    // (today's date will be used by the service, but we rename the sheet afterwards to simulate)
    service.writeQuotes(List.of(q), providers(), outputFile);
    // Rename the today sheet to simulate a different day
    String todayName = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    String fakeName = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    try (FileInputStream fis = new FileInputStream(outputFile);
        Workbook wb = new XSSFWorkbook(fis)) {
      int idx = wb.getSheetIndex(todayName);
      if (idx >= 0 && wb.getSheet(fakeName) == null) {
        wb.setSheetName(idx, fakeName);
      }
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile)) {
        wb.write(fos);
      }
    }
  }

  private Workbook open(File file) throws Exception {
    return new XSSFWorkbook(new FileInputStream(file));
  }
}
