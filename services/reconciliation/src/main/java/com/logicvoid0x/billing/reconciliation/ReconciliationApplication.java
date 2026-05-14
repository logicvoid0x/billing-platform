package com.logicvoid0x.billing.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the reconciliation service. */
@SpringBootApplication
public class ReconciliationApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(ReconciliationApplication.class, args);
  }
}
