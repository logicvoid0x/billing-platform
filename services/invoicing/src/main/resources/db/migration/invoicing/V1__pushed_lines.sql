-- Invoice lines pushed to Stripe (design: "Stripe pushed state | hash(org, sku, period,
-- ledger_version) | Append-only"). The push key is also the Stripe idempotency key, so a re-run
-- of close sends the same request and Stripe returns the item it already created.

CREATE TABLE pushed_line (
  push_key        text        PRIMARY KEY CHECK (push_key ~ '^[0-9a-f]{64}$'),
  org_id          text        NOT NULL,
  sku_id          text        NOT NULL,
  period_start    timestamptz NOT NULL,
  ledger_version  integer     NOT NULL CHECK (ledger_version >= 1),
  amount_cents    bigint      NOT NULL,
  stripe_item_id  text        NOT NULL,
  pushed_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, sku_id, period_start, ledger_version)
);

CREATE FUNCTION pushed_line_append_only() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pushed_line is append-only' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER pushed_line_no_update BEFORE UPDATE OR DELETE ON pushed_line
  FOR EACH ROW EXECUTE FUNCTION pushed_line_append_only();
