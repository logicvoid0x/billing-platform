package com.logicvoid0x.billing.catalog.launch;

import com.logicvoid0x.billing.domain.MeterCode;
import java.util.Objects;

/**
 * A model-registry row: {@code model} in {@code serviceTier} emits usage on {@code meter}.
 *
 * @param model model name as it appears on usage events
 * @param serviceTier service tier as it appears on usage events
 * @param meter the meter the model emits
 */
public record ModelOffering(String model, String serviceTier, MeterCode meter) {
  /** Validates the row. */
  public ModelOffering {
    if (model == null || model.isBlank() || serviceTier == null || serviceTier.isBlank()) {
      throw new IllegalArgumentException("model and serviceTier must not be blank");
    }
    Objects.requireNonNull(meter, "meter");
  }
}
