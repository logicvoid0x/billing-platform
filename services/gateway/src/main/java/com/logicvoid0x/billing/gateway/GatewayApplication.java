package com.logicvoid0x.billing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the gateway service. */
@SpringBootApplication
public class GatewayApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
