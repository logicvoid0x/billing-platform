package com.logicvoid0x.billing.ratingengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the rating-engine service. */
@SpringBootApplication
public class RatingEngineApplication {
  /** Starts the service. */
  public static void main(String[] args) {
    SpringApplication.run(RatingEngineApplication.class, args);
  }
}
