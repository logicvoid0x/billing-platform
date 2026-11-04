package com.logicvoid0x.billing.reconciliation.stripe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reconciliation against Stripe (requirement: "compare invoiced totals with the rated ledger before
 * finalization; payments vs TOP_UP daily"). Pure comparisons in long cents; callers load each side.
 */
public final class StripeChecks {
  private StripeChecks() {}

  /**
   * Draft invoice lines against the rated ledger, per SKU, for one org and period. Finalization
   * must wait until this is empty.
   *
   * @param invoiced cents per SKU on the draft invoice
   * @param rated rated-ledger cents per SKU for the period (all versions)
   * @return one line per SKU whose amounts differ, or that is on only one side
   */
  public static List<String> invoiceMismatches(
      Map<String, Long> invoiced, Map<String, Long> rated) {
    List<String> out = new ArrayList<>();
    TreeSet<String> skus = new TreeSet<>(invoiced.keySet());
    skus.addAll(rated.keySet());
    for (String sku : skus) {
      long inv = invoiced.getOrDefault(sku, 0L);
      long r = rated.getOrDefault(sku, 0L);
      if (inv != r) {
        out.add(sku + ": invoiced " + inv + " rated " + r);
      }
    }
    return out;
  }

  /**
   * Succeeded Stripe payments against TOP_UP entries for one day.
   *
   * @param payments cents per succeeded payment intent ID
   * @param topUps cents per Stripe-keyed TOP_UP, by payment intent ID (key stripe:<id>)
   * @return payments without a top-up, top-ups without a payment, and amount mismatches
   */
  public static List<String> paymentMismatches(
      Map<String, Long> payments, Map<String, Long> topUps) {
    List<String> out = new ArrayList<>();
    TreeSet<String> ids = new TreeSet<>(payments.keySet());
    ids.addAll(topUps.keySet());
    for (String id : ids) {
      Long paid = payments.get(id);
      Long credited = topUps.get(id);
      if (paid == null) {
        out.add(id + ": TOP_UP " + credited + " without a Stripe payment");
      } else if (credited == null) {
        out.add(id + ": payment " + paid + " without a TOP_UP (webhook lost; replay it)");
      } else if (!paid.equals(credited)) {
        out.add(id + ": payment " + paid + " but TOP_UP " + credited);
      }
    }
    return out;
  }
}
