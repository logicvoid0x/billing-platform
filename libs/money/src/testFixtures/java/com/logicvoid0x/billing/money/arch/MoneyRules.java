package com.logicvoid0x.billing.money.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Money code uses {@code long} cents only (docs/design.md, "Money representation"): no {@code
 * double}, {@code float}, {@code BigDecimal} or {@code BigInteger}. Two layers: an ArchUnit rule on
 * the compiled classes (fields, signatures, dependencies) and a source scan that also sees local
 * variables and casts, which bytecode analysis cannot.
 */
public final class MoneyRules {
  private MoneyRules() {}

  private static final Set<String> INEXACT_PRIMITIVES = Set.of("double", "float");

  private static final DescribedPredicate<JavaClass> INEXACT_TYPE =
      DescribedPredicate.describe(
          "double, float, Double, Float, BigDecimal or BigInteger",
          c ->
              INEXACT_PRIMITIVES.contains(c.getName())
                  || c.isEquivalentTo(Double.class)
                  || c.isEquivalentTo(Float.class)
                  || c.isEquivalentTo(BigDecimal.class)
                  || c.isEquivalentTo(BigInteger.class)
                  || (c.isArray()
                      && INEXACT_PRIMITIVES.contains(c.getBaseComponentType().getName())));

  private static final ArchCondition<JavaCodeUnit> HAVE_INEXACT_SIGNATURE =
      new ArchCondition<>("have a double, float, BigDecimal or BigInteger parameter or return") {
        @Override
        public void check(JavaCodeUnit unit, ConditionEvents events) {
          boolean bad =
              INEXACT_TYPE.test(unit.getRawReturnType())
                  || unit.getRawParameterTypes().stream().anyMatch(INEXACT_TYPE);
          events.add(new SimpleConditionEvent(unit, bad, unit.getFullName()));
        }
      };

  /** The ArchUnit layer: apply with {@code @ArchTest} to a module's main classes. */
  public static final ArchRule EXACT_MONEY_ONLY =
      CompositeArchRule.of(
              noFields()
                  .should()
                  .haveRawType(INEXACT_TYPE)
                  .allowEmptyShould(true)
                  .because("amounts are long cents"))
          .and(
              noCodeUnits()
                  .should(HAVE_INEXACT_SIGNATURE)
                  .allowEmptyShould(true)
                  .because("amounts are long cents"))
          .and(
              noClasses()
                  .should()
                  .dependOnClassesThat(INEXACT_TYPE)
                  .allowEmptyShould(true)
                  .because("amounts are long cents"))
          .and(
              noClasses()
                  .should()
                  .callCodeUnitWhere(
                      DescribedPredicate.describe(
                          "the target takes or returns double, float, BigDecimal or BigInteger",
                          (JavaCall<?> call) ->
                              INEXACT_TYPE.test(call.getTarget().getRawReturnType())
                                  || call.getTarget().getRawParameterTypes().stream()
                                      .anyMatch(INEXACT_TYPE)))
                  .allowEmptyShould(true)
                  .because("e.g. Math.round(double) means floating-point arithmetic"));

  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
  private static final Pattern INEXACT_TOKEN =
      Pattern.compile(
          "\\b(double|float|Double|Float|BigDecimal|BigInteger)\\b"
              // floating-point literals: 0.5, 1., .5, 1e3, 2d, 3f (not ints like 10L or 0x1F)
              + "|(?<![\\w.])(\\d[\\d_]*\\.[\\d_]*(?:[eE][+-]?\\d+)?[dDfF]?"
              + "|\\.\\d[\\d_]*(?:[eE][+-]?\\d+)?[dDfF]?"
              + "|\\d[\\d_]*[eE][+-]?\\d+[dDfF]?"
              + "|\\d[\\d_]*[dDfF])(?![\\w.])");

  /**
   * The source layer: fails if any Java file under {@code sourceRoot} uses an inexact type outside
   * comments and string literals. Returns the violations instead of throwing when asked.
   */
  public static List<String> inexactTypeUsages(Path sourceRoot) {
    if (!Files.isDirectory(sourceRoot)) {
      return List.of();
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      for (Path file :
          (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
        String code = Files.readString(file, StandardCharsets.UTF_8);
        code = BLOCK_COMMENT.matcher(code).replaceAll(" ");
        code = LINE_COMMENT.matcher(code).replaceAll(" ");
        code = STRING_LITERAL.matcher(code).replaceAll("\"\"");
        Matcher m = INEXACT_TOKEN.matcher(code);
        while (m.find()) {
          violations.add(sourceRoot.relativize(file) + ": " + m.group());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return violations;
  }

  /** Asserts that {@link #inexactTypeUsages} finds nothing under {@code sourceRoot}. */
  public static void assertNoInexactTypesInSources(Path sourceRoot) {
    List<String> violations = inexactTypeUsages(sourceRoot);
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "inexact types in money code (use long cents):\n  " + String.join("\n  ", violations));
    }
  }
}
