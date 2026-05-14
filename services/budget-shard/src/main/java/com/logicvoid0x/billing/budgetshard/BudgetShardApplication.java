package com.logicvoid0x.billing.budgetshard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the budget-shard service. */
@SpringBootApplication
public class BudgetShardApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(BudgetShardApplication.class, args);
  }
}
