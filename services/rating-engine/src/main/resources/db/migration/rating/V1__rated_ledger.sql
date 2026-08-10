-- Rated ledger (design: "append-only, versioned, corrections as new rows").
-- One RATED row per (org, SKU, event hour, rating_version). A re-rating writes a new
-- rating_version whose ADJUSTMENT rows carry only the difference (P7.3). Rows are never updated
-- or deleted. Amounts are bigint cents. Each hour's cents come from running period totals,
-- F(total_before + quantity) - F(total_before), so a period's rows telescope to the period total
-- rounded once.

CREATE TABLE rated_ledger (
  org_id              text        NOT NULL CHECK (btrim(org_id) <> ''),
  sku_id              text        NOT NULL CHECK (btrim(sku_id) <> ''),
  event_hour          timestamptz NOT NULL CHECK (mod(extract(epoch FROM event_hour), 3600) = 0),
  rating_version      integer     NOT NULL CHECK (rating_version >= 1),
  entry_type          text        NOT NULL CHECK (entry_type IN ('RATED', 'ADJUSTMENT')),
  quantity            bigint      NOT NULL CHECK (quantity >= 0),
  period_start        timestamptz NOT NULL,
  total_before        bigint      NOT NULL CHECK (total_before >= 0),
  amount_cents        bigint      NOT NULL,
  catalog_version     text        NOT NULL,
  rating_code_version text        NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, sku_id, event_hour, rating_version),
  CHECK (entry_type = 'ADJUSTMENT' OR amount_cents >= 0)
);

CREATE FUNCTION rated_ledger_append_only() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'rated_ledger is append-only; post an adjustment instead'
    USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER rated_ledger_no_update BEFORE UPDATE OR DELETE ON rated_ledger
  FOR EACH ROW EXECUTE FUNCTION rated_ledger_append_only();
CREATE TRIGGER rated_ledger_no_truncate BEFORE TRUNCATE ON rated_ledger
  FOR EACH STATEMENT EXECUTE FUNCTION rated_ledger_append_only();
