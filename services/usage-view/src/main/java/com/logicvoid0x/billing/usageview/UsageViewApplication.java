package com.logicvoid0x.billing.usageview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the usage-view service. */
@SpringBootApplication
public class UsageViewApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(UsageViewApplication.class, args);
  }
}
