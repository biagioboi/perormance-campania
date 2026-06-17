package it.unisa.performance.config;

import it.unisa.performance.service.DemoDataService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements ApplicationRunner {

  private final DemoDataService demoDataService;

  public DataInitializer(DemoDataService demoDataService) {
    this.demoDataService = demoDataService;
  }

  @Override
  public void run(ApplicationArguments args) {
    demoDataService.seedIfEmpty();
  }
}
