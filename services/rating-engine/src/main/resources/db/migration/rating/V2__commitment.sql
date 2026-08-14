-- Committed use (P7.2; assumption in open-questions Q8). A commitment replaces tier state: usage
-- is charged at a flat rate and draws the commitment down; the rest is overage.

CREATE TABLE commitment (
  org_id           text        NOT NULL CHECK (btrim(org_id) <> ''),
  period_start     timestamptz NOT NULL,
  commit_cents     bigint      NOT NULL CHECK (commit_cents > 0),
  contract_version integer     NOT NULL CHECK (contract_version > 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, period_start)
);

CREATE TRIGGER commitment_no_update BEFORE UPDATE OR DELETE ON commitment
  FOR EACH ROW EXECUTE FUNCTION rated_ledger_append_only();

-- How each rated row was priced, and the part of it the commitment covered.
ALTER TABLE rated_ledger
  ADD COLUMN pricing_path   text   NOT NULL DEFAULT 'TIERED' CHECK (pricing_path IN ('TIERED', 'COMMIT')),
  ADD COLUMN drawdown_cents bigint NOT NULL DEFAULT 0;
ALTER TABLE rated_ledger
  ADD CONSTRAINT rated_ledger_drawdown_check CHECK (
    entry_type = 'ADJUSTMENT'
    OR (drawdown_cents >= 0 AND drawdown_cents <= amount_cents
        AND (pricing_path = 'COMMIT' OR drawdown_cents = 0)));
