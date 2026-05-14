package com.logicvoid0x.billing.balanceledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the balance-ledger service. */
@SpringBootApplication
public class BalanceLedgerApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(BalanceLedgerApplication.class, args);
  }
}
