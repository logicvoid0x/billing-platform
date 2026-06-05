package com.logicvoid0x.billing.money;

import com.logicvoid0x.billing.money.arch.MainClassesOnly;
import com.logicvoid0x.billing.money.arch.MoneyRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Money code is long cents only: see docs/decisions/0002-money-in-cents.md. */
@AnalyzeClasses(packages = "com.logicvoid0x.billing.money", importOptions = MainClassesOnly.class)
class MoneyTypesArchTest {
  @ArchTest static final ArchRule exactMoneyOnly = MoneyRules.EXACT_MONEY_ONLY;

  @Test
  void sourcesUseNoInexactTypes() {
    MoneyRules.assertNoInexactTypesInSources(Path.of("src/main/java"));
  }
}
