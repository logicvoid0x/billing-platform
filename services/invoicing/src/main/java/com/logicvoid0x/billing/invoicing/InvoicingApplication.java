package com.logicvoid0x.billing.invoicing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the invoicing service. */
@SpringBootApplication
public class InvoicingApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(InvoicingApplication.class, args);
  }
}
