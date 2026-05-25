package com.logicvoid0x.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Codes are persisted and sent on the wire; these golden values must never change. */
class DomainCodesTest {
  @Test
  void meterCodesArePermanentStrings() {
    assertThat(MeterCode.INPUT_TOKENS.code()).isEqualTo("input_tokens");
    assertThat(MeterCode.OUTPUT_TOKENS.code()).isEqualTo("output_tokens");
    assertThat(MeterCode.CACHE_WRITE_TOKENS.code()).isEqualTo("cache_write_tokens");
    assertThat(MeterCode.CACHE_READ_TOKENS.code()).isEqualTo("cache_read_tokens");
    assertThat(MeterCode.of("output_tokens")).isEqualTo(MeterCode.OUTPUT_TOKENS);
    assertThat(MeterCode.of("web_search_calls").code()).isEqualTo("web_search_calls");
  }

  @Test
  void meterCodesAreValidated() {
    for (String bad :
        new String[] {"", "Output", "1tokens", "out-put", "out put", "x".repeat(65)}) {
      assertThatThrownBy(() -> MeterCode.of(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void modeCodes() {
    assertThat(codes(Mode.values(), Mode::code)).containsExactly("lease", "direct", "deny");
    for (Mode m : Mode.values()) {
      assertThat(Mode.fromCode(m.code())).isEqualTo(m);
    }
    assertThatThrownBy(() -> Mode.fromCode("LEASE")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void budgetKindAndUnitCodes() {
    assertThat(codes(BudgetKind.values(), BudgetKind::code))
        .containsExactly("balance", "spend_limit", "allowance");
    assertThat(codes(BudgetUnit.values(), BudgetUnit::code))
        .containsExactly("cents", "usage_units");
    assertThat(BudgetKind.BALANCE.unit()).isEqualTo(BudgetUnit.CENTS);
    assertThat(BudgetKind.SPEND_LIMIT.unit()).isEqualTo(BudgetUnit.CENTS);
    assertThat(BudgetKind.ALLOWANCE.unit()).isEqualTo(BudgetUnit.USAGE_UNITS);
    for (BudgetKind k : BudgetKind.values()) {
      assertThat(BudgetKind.fromCode(k.code())).isEqualTo(k);
    }
    for (BudgetUnit u : BudgetUnit.values()) {
      assertThat(BudgetUnit.fromCode(u.code())).isEqualTo(u);
    }
  }

  @Test
  void failPolicyCodes() {
    assertThat(FailPolicy.open(2_000).code()).isEqualTo("open");
    assertThat(FailPolicy.closed().code()).isEqualTo("closed");
    assertThat(FailPolicy.open(2_000).failOpenAllowanceCents()).isEqualTo(2_000);
    assertThat(FailPolicy.closed().failOpenAllowanceCents()).isZero();
    assertThat(FailPolicy.closed().isOpen()).isFalse();
    assertThatThrownBy(() -> FailPolicy.open(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  private static <T> String[] codes(T[] values, Function<T, String> code) {
    return Arrays.stream(values).map(code).toArray(String[]::new);
  }
}
