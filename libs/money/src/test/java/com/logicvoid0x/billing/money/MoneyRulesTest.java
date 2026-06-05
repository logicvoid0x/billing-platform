package com.logicvoid0x.billing.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.money.arch.MoneyRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The money rule must actually catch violations, not just pass on clean code. */
class MoneyRulesTest {
  /** Planted violation: a double field. */
  static final class DoubleField {
    final double amount;

    DoubleField(long cents) {
      this.amount = cents;
    }
  }

  /** Planted violation: a BigDecimal return type. */
  static final class BigDecimalReturn {
    static BigDecimal amount(long cents) {
      return BigDecimal.valueOf(cents);
    }
  }

  /** Planted violation: a float parameter. */
  static final class FloatParameter {
    static long cents(float dollars) {
      return Math.round(dollars * 100);
    }
  }

  /** Planted violation: a BigInteger dependency inside a method body. */
  static final class BigIntegerCall {
    static long square(long cents) {
      return BigInteger.valueOf(cents).pow(2).longValueExact();
    }
  }

  /** Planted violation: floating-point arithmetic with no inexact type in any signature. */
  static final class FloatingCall {
    static long half(long cents) {
      return Math.round(cents * 0.5d);
    }
  }

  @Test
  void archRuleFlagsEachKindOfViolation() {
    for (Class<?> planted :
        new Class<?>[] {
          DoubleField.class,
          BigDecimalReturn.class,
          FloatParameter.class,
          BigIntegerCall.class,
          FloatingCall.class
        }) {
      JavaClasses classes = new ClassFileImporter().importClasses(planted);
      assertThatThrownBy(() -> MoneyRules.EXACT_MONEY_ONLY.check(classes))
          .as(planted.getSimpleName())
          .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void archRuleAcceptsLongCents() {
    JavaClasses classes = new ClassFileImporter().importClasses(Cents.class, Cents.Rate.class);
    assertThatCode(() -> MoneyRules.EXACT_MONEY_ONLY.check(classes)).doesNotThrowAnyException();
  }

  @Test
  void sourceScanSeesLocalsButIgnoresCommentsAndStrings(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("Ok.java"),
        """
        /** Never BigDecimal or double. */
        class Ok {
          // float is banned
          String s = "double";
          long cents;
        }
        """,
        StandardCharsets.UTF_8);
    assertThat(MoneyRules.inexactTypeUsages(dir)).isEmpty();

    Files.writeString(
        dir.resolve("Bad.java"),
        """
        class Bad {
          long f(long c) {
            double rate = c * 0.5;
            return (long) rate;
          }
        }
        """,
        StandardCharsets.UTF_8);
    assertThat(MoneyRules.inexactTypeUsages(dir))
        .containsExactly("Bad.java: double", "Bad.java: 0.5");
    assertThatThrownBy(() -> MoneyRules.assertNoInexactTypesInSources(dir))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Bad.java");
  }
}
