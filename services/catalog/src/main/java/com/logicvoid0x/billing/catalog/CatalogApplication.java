package com.logicvoid0x.billing.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the catalog service. */
@SpringBootApplication
public class CatalogApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(CatalogApplication.class, args);
  }
}
