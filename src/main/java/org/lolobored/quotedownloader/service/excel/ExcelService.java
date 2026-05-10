package org.lolobored.quotedownloader.service.excel;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.lolobored.quotedownloader.model.Quote;
import org.lolobored.quotedownloader.model.config.Provider;

public interface ExcelService {

  void writeQuotes(List<Quote> quotes, List<Provider> providers, File outputFile)
      throws IOException;
}
