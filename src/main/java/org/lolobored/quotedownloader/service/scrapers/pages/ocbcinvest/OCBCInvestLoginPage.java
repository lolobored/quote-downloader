package org.lolobored.quotedownloader.service.scrapers.pages.ocbcinvest;

import java.time.Duration;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OCBCInvestLoginPage {

  private static final Logger logger = LoggerFactory.getLogger(OCBCInvestLoginPage.class);

  // Selectors verified from the live site (same portal as bank-statements)
  private static final By USERNAME_FIELD = By.id("textAccessCode");
  private static final By PASSWORD_FIELD = By.id("textLoginPin");
  private static final By LOGIN_BUTTON = By.id("cmdLogin");

  // OCBC animates the username field into focus — needs a short pause before typing
  private static final Duration ANIMATION_PAUSE = Duration.ofSeconds(2);
  // Extra delay before submitting to let any pre-login checks complete
  private static final Duration PRE_SUBMIT_PAUSE = Duration.ofSeconds(2);

  private final WebDriver driver;
  private final WebDriverWait wait;

  public OCBCInvestLoginPage(WebDriver driver, int waitSeconds) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, Duration.ofSeconds(waitSeconds));
  }

  /**
   * Logs in to OCBC internet banking. After this returns, the driver is on the post-login dashboard
   * and ready to navigate to the investment/wealth section. If MFA (hardware token or push
   * notification) is triggered, the waitTime budget covers it.
   */
  public void login(String url, String username, String password) throws InterruptedException {
    driver.get(url);

    wait.until(ExpectedConditions.visibilityOfElementLocated(USERNAME_FIELD));
    WebElement usernameField = driver.findElement(USERNAME_FIELD);
    new Actions(driver).moveToElement(usernameField).pause(ANIMATION_PAUSE).build().perform();
    usernameField.sendKeys(username);

    wait.until(ExpectedConditions.visibilityOfElementLocated(PASSWORD_FIELD));
    driver.findElement(PASSWORD_FIELD).sendKeys(password);

    wait.until(ExpectedConditions.elementToBeClickable(LOGIN_BUTTON));
    Thread.sleep(PRE_SUBMIT_PAUSE.toMillis());
    driver.findElement(LOGIN_BUTTON).click();

    logger.info("OCBC login submitted — approve the push notification on your phone...");
    // Full waitTime budget covers the push notification approval delay
    wait.until(ExpectedConditions.urlContains("/cfo/dashboard"));
    logger.info("OCBC login successful — on dashboard");
  }
}
