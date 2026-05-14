package com.logicvoid0x.billing.inferencesim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the inference-sim service. */
@SpringBootApplication
public class InferenceSimApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(InferenceSimApplication.class, args);
  }
}
