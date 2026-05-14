package com.logicvoid0x.billing.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the payments service. */
@SpringBootApplication
public class PaymentsApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(PaymentsApplication.class, args);
  }
}
