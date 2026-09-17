package com.logicvoid0x.billing.invoicing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Before an invoice is finalized in Stripe, it is read back and compared with the rated ledger
 * (plan: "require every line amount and the subtotal to equal the rated ledger to the cent").
 * Finalizing is refused on any difference, so a customer never receives an invoice the rated ledger
 * does not support.
 */
public final class FinalizeGuard {
  /** The invoice as Stripe holds it. */
  public record StripeInvoice(Map<String, Long> centsByPushKey, long subtotalCents) {
    /** Copies the lines. */
    public StripeInvoice {
      centsByPushKey = Map.copyOf(centsByPushKey);
    }
  }

  private FinalizeGuard() {}

  /**
   * Differences between the Stripe invoice and what should be on it.
   *
   * @param stripe the invoice read back from Stripe, lines keyed by our push key (metadata)
   * @param expected rated cents per push key (each line's hash(org, sku, period, version))
   * @return one entry per difference; empty means the invoice may be finalized
   */
  public static List<String> differences(StripeInvoice stripe, Map<String, Long> expected) {
    List<String> out = new ArrayList<>();
    TreeSet<String> keys = new TreeSet<>(stripe.centsByPushKey().keySet());
    keys.addAll(expected.keySet());
    long expectedTotal = 0;
    for (String k : keys) {
      Long s = stripe.centsByPushKey().get(k);
      Long e = expected.get(k);
      if (e != null) {
        expectedTotal = Math.addExact(expectedTotal, e);
      }
      if (s == null) {
        out.add(k + ": missing on the Stripe invoice (expected " + e + ")");
      } else if (e == null) {
        out.add(k + ": on the Stripe invoice (" + s + ") but not in the rated ledger");
      } else if (!s.equals(e)) {
        out.add(k + ": Stripe " + s + " rated " + e);
      }
    }
    if (stripe.subtotalCents() != expectedTotal) {
      out.add("subtotal: Stripe " + stripe.subtotalCents() + " rated " + expectedTotal);
    }
    return out;
  }

  /** Throws unless the invoice may be finalized. */
  public static void requireFinalizable(StripeInvoice stripe, Map<String, Long> expected) {
    List<String> d = differences(stripe, expected);
    if (!d.isEmpty()) {
      throw new IllegalStateException("invoice does not match the rated ledger: " + d);
    }
  }
}
