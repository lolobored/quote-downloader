package org.lolobored.quotedownloader.service.otp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OtpGate {

  private static final Logger logger = LoggerFactory.getLogger(OtpGate.class);

  // Only one provider at a time may prompt for an OTP — prevents concurrent requests
  // from overlapping and confusing the user about which code to enter.
  private final Semaphore semaphore = new Semaphore(1);

  // false = read from stdin (default, bash mode); true = poll a file (claude/automation mode)
  private boolean fileMode = false;

  public void setFileMode(boolean fileMode) {
    this.fileMode = fileMode;
  }

  public String requestOtp(String providerName, Path otpFile) throws InterruptedException {
    semaphore.acquire();
    try {
      return fileMode ? readFromFile(providerName, otpFile) : readFromConsole(providerName);
    } finally {
      semaphore.release();
    }
  }

  private String readFromConsole(String providerName) throws InterruptedException {
    try {
      System.out.println();
      System.out.println(">>> " + providerName + " SMS OTP required — enter code and press Enter:");
      System.out.print(">>> OTP: ");
      System.out.flush();
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
      String code = reader.readLine();
      if (code == null || code.isBlank()) {
        throw new RuntimeException("No OTP entered for " + providerName);
      }
      return code.trim();
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to read OTP from console for " + providerName, e);
    }
  }

  private String readFromFile(String providerName, Path otpFile) throws InterruptedException {
    try {
      Files.deleteIfExists(otpFile);
    } catch (Exception ignored) {
    }
    logger.info("=== {} SMS OTP required ===", providerName);
    logger.info("Write your 6-digit code to: {}", otpFile);
    logger.info("  e.g.  echo 123456 > {}", otpFile);
    logger.info("==========================");
    while (true) {
      if (Files.exists(otpFile)) {
        try {
          String code = Files.readString(otpFile).trim();
          Files.deleteIfExists(otpFile);
          logger.info("[{}] OTP code read", providerName);
          return code;
        } catch (Exception e) {
          logger.warn("[{}] Could not read OTP file, retrying: {}", providerName, e.getMessage());
        }
      }
      Thread.sleep(1000);
    }
  }
}
