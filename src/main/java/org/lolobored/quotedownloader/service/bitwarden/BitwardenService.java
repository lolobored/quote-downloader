package org.lolobored.quotedownloader.service.bitwarden;

import java.io.IOException;
import org.lolobored.quotedownloader.model.config.Provider;

public interface BitwardenService {

  void checkVaultAccess() throws IOException, InterruptedException;

  void resolveCredentials(Provider provider) throws IOException, InterruptedException;
}
