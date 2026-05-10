package org.lolobored.quotedownloader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.io.FileUtils;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Provider;
import org.lolobored.quotedownloader.service.bitwarden.BitwardenService;
import org.lolobored.quotedownloader.service.excel.ExcelService;
import org.lolobored.quotedownloader.service.scrapers.GreatEasternService;
import org.lolobored.quotedownloader.service.scrapers.OCBCInvestService;
import org.lolobored.quotedownloader.service.scrapers.QuoteProviderService;
import org.lolobored.quotedownloader.service.scrapers.RoyalLondonService;
import org.lolobored.quotedownloader.service.scrapers.YahooFinanceService;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class QuoteDownloaderApplication implements ApplicationRunner {

  @Autowired private YahooFinanceService yahooFinanceService;
  @Autowired private GreatEasternService greatEasternService;
  @Autowired private OCBCInvestService ocbcInvestService;
  @Autowired private RoyalLondonService royalLondonService;
  @Autowired private BitwardenService bitwardenService;
  @Autowired private ExcelService excelService;
  @Autowired private org.lolobored.quotedownloader.service.otp.OtpGate otpGate;

  private static final String YAHOO = "yahoo";
  private static final String GREAT_EASTERN = "great eastern";
  private static final String OCBC_INVEST = "ocbc";
  private static final String ROYAL_LONDON = "royal london";

  private static final int CHROME_BROWSER = 0;
  private static final int FIREFOX_BROWSER = 1;
  private static final int SAFARI_BROWSER = 2;

  private final Logger logger = LoggerFactory.getLogger(QuoteDownloaderApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(QuoteDownloaderApplication.class, args);
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!args.containsOption("json")) {
      logger.error(
          "Option --json is mandatory and should contain the path to the JSON config file");
      System.exit(-1);
    }
    String jsonFilePath = args.getOptionValues("json").get(0);
    logger.info("JSON config [{}]", new File(jsonFilePath).getAbsolutePath());
    if (!new File(jsonFilePath).exists()) {
      logger.error("JSON file does not exist at [{}]", jsonFilePath);
      System.exit(-1);
    }

    File outputDirectory =
        args.containsOption("output")
            ? new File(args.getOptionValues("output").get(0))
            : new File(System.getProperty("user.home"), "Downloads");
    File screenshotsDirectory =
        args.containsOption("screenshots")
            ? new File(args.getOptionValues("screenshots").get(0))
            : new File(System.getProperty("user.home"), "Downloads");

    int browserType = CHROME_BROWSER;
    if (args.getOptionValues("browser") != null) {
      browserType =
          switch (args.getOptionValues("browser").get(0).toLowerCase().trim()) {
            case "firefox" -> FIREFOX_BROWSER;
            case "safari" -> SAFARI_BROWSER;
            default -> CHROME_BROWSER;
          };
    }
    boolean headed = args.containsOption("headed");
    if (args.containsOption("otp-file")) {
      otpGate.setFileMode(true);
      logger.info("OTP mode: file-based (/tmp/<provider>-otp.txt)");
    } else {
      logger.info("OTP mode: console (interactive stdin)");
    }

    switch (browserType) {
      case CHROME_BROWSER -> WebDriverManager.chromedriver().setup();
      case FIREFOX_BROWSER -> WebDriverManager.firefoxdriver().setup();
    }

    ObjectMapper objectMapper = new ObjectMapper();
    List<Provider> providers =
        objectMapper.readValue(
            Files.readString(Path.of(jsonFilePath)), new TypeReference<List<Provider>>() {});

    boolean anyEnabledProviderUsesBitwarden =
        providers.stream()
            .filter(Provider::isEnabled)
            .anyMatch(p -> p.getBitwardenItemName() != null && !p.getBitwardenItemName().isEmpty());
    if (anyEnabledProviderUsesBitwarden) {
      bitwardenService.checkVaultAccess();
    }
    for (Provider provider : providers) {
      if (provider.isEnabled()) {
        bitwardenService.resolveCredentials(provider);
      }
    }

    final int finalBrowserType = browserType;
    final File finalScreenshotsDirectory = screenshotsDirectory;
    List<Callable<List<Quote>>> tasks = new ArrayList<>();
    List<String> taskProviderNames = new ArrayList<>();

    for (Provider provider : providers) {
      if (!provider.isEnabled()) {
        logger.info("Skipping disabled provider [{}]", provider.getName());
        continue;
      }
      QuoteProviderService service = resolveService(provider.getName());
      if (service == null) {
        logger.warn("No service registered for provider [{}] — skipping", provider.getName());
        continue;
      }

      taskProviderNames.add(provider.getName());
      final boolean finalHeaded = headed;
      tasks.add(
          () -> {
            WebDriver driver = createWebDriver(finalBrowserType, finalHeaded);
            try {
              return service.fetchQuotes(driver, provider);
            } catch (Exception e) {
              saveErrorScreenshot(driver, provider.getName(), finalScreenshotsDirectory);
              throw e;
            } finally {
              driver.quit();
            }
          });
    }

    boolean sequential = args.containsOption("sequential");
    ExecutorService executor =
        sequential ? Executors.newSingleThreadExecutor() : Executors.newCachedThreadPool();
    List<Future<List<Quote>>> futures;
    try {
      futures = executor.invokeAll(tasks);
    } finally {
      executor.shutdown();
    }

    List<Quote> quotes = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
      String providerName = taskProviderNames.get(i);
      try {
        quotes.addAll(futures.get(i).get());
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        String msg =
            cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        failures.add(providerName + ": " + msg);
        logger.error("Provider [{}] failed", providerName, cause);
      }
    }

    if (!failures.isEmpty()) {
      logger.error("╔══════════════════════════════════════╗");
      logger.error("║       QUOTE FETCH FAILURES           ║");
      logger.error("╠══════════════════════════════════════╣");
      failures.forEach(f -> logger.error("║  ✗ {}", f));
      logger.error("╚══════════════════════════════════════╝");
    }
    if (quotes.isEmpty() && !failures.isEmpty()) {
      throw new RuntimeException("All providers failed — no quotes to write");
    }

    outputDirectory.mkdirs();
    File outputFile = new File(outputDirectory, "quotes.xlsx");
    excelService.writeQuotes(quotes, providers, outputFile);
    logger.info("Wrote {} quotes to {}", quotes.size(), outputFile.getAbsolutePath());
  }

  private QuoteProviderService resolveService(String providerName) {
    return switch (providerName.toLowerCase()) {
      case YAHOO -> yahooFinanceService;
      case GREAT_EASTERN -> greatEasternService;
      case OCBC_INVEST -> ocbcInvestService;
      case ROYAL_LONDON -> royalLondonService;
      default -> null;
    };
  }

  private WebDriver createWebDriver(int browserType, boolean headed) {
    return switch (browserType) {
      case CHROME_BROWSER -> {
        ChromeOptions opts = new ChromeOptions();
        if (!headed) {
          opts.addArguments("--headless=new");
        }
        opts.addArguments(
            "--window-size=1280,900",
            "--disable-popup-blocking",
            "--disable-features=FedCm,FedCmWithoutWellKnownEnforcement",
            "--no-first-run",
            "--no-default-browser-check",
            "--incognito");
        yield new ChromeDriver(opts);
      }
      case FIREFOX_BROWSER -> {
        FirefoxOptions opts = new FirefoxOptions();
        if (!headed) {
          opts.addArguments("--headless");
        }
        yield new FirefoxDriver(opts);
      }
      default -> new SafariDriver(new SafariOptions());
    };
  }

  private void saveErrorScreenshot(WebDriver driver, String providerName, File screenshotsDir) {
    try {
      File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
      String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      File dest = new File(screenshotsDir, "error-" + providerName + "-" + timestamp + ".png");
      FileUtils.copyFile(screenshot, dest);
      logger.error("Screenshot for [{}] saved to: {}", providerName, dest.getAbsolutePath());
    } catch (Exception e) {
      logger.warn("Could not take error screenshot for [{}]: {}", providerName, e.getMessage());
    }
  }
}
