package com.logicvoid0x.billing.money.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** Imports production classes only: no test, test-fixture or generated-test classes. */
public final class MainClassesOnly implements ImportOption {
  @Override
  public boolean includes(Location location) {
    return location.contains("/classes/java/main/");
  }
}
