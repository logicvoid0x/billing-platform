-- Catalog schema: SKUs, context-band rules, price book, contract overrides, allowance weight book.
-- Rules (design: "SKU catalog ... never edited; effective-dated"; ADR 0004):
--   * Rows are never edited or deleted. A change is a new effective-dated row. The only update
--     allowed is closing an open-ended row (effective_until NULL -> a time not before the current
--     hour), so a new row can follow it; history that was already rated never changes.
--   * Periods are half-open [effective_from, effective_until); NULL until means open-ended.
--     Overlapping periods for the same business key are rejected by exclusion constraints.
--   * Money is bigint cents with a bigint per-units divisor (Cents.Rate). No numeric/float types.
--   * Every row has a business primary key, so re-sending a write is a no-op (ON CONFLICT DO NOTHING).
--   * Price periods start and end on an hour boundary, so each usage hour has exactly one price.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ------------------------------------------------------------------ immutability guards
CREATE FUNCTION is_hour(t timestamptz) RETURNS boolean
  LANGUAGE sql IMMUTABLE STRICT
  AS $$ SELECT mod(extract(epoch FROM t), 3600) = 0 $$;

-- Rejects every UPDATE except closing an open period, and every DELETE.
CREATE FUNCTION reject_edit() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'catalog rows are never deleted (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF (to_jsonb(NEW) - 'effective_until') IS DISTINCT FROM (to_jsonb(OLD) - 'effective_until') THEN
    RAISE EXCEPTION 'catalog rows are never edited (%.%): add a new effective-dated row',
      TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF OLD.effective_until IS NOT NULL OR NEW.effective_until IS NULL THEN
    RAISE EXCEPTION 'only an open-ended period can be closed (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NEW.effective_until < to_timestamp(floor(extract(epoch FROM now()) / 3600) * 3600) THEN
    RAISE EXCEPTION 'a period cannot be closed before the current hour (%.%)',
      TG_TABLE_SCHEMA, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END $$;

-- Tier rows belong to one price row and never change at all.
CREATE FUNCTION reject_any_change() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'catalog rows are never edited or deleted (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'integrity_constraint_violation';
END $$;

-- ------------------------------------------------------------------ SKUs
CREATE TABLE sku (
  sku_id          text        PRIMARY KEY CHECK (btrim(sku_id) <> ''),
  sku_key         text        NOT NULL UNIQUE CHECK (btrim(sku_key) <> ''),
  meter_code      text        NOT NULL CHECK (meter_code ~ '^[a-z][a-z0-9_]{0,63}$'),
  model           text        NOT NULL CHECK (btrim(model) <> ''),
  service_tier    text        NOT NULL CHECK (btrim(service_tier) <> ''),
  context_band    text        NOT NULL CHECK (btrim(context_band) <> ''),
  effective_from  timestamptz NOT NULL,
  effective_until timestamptz CHECK (effective_until > effective_from),
  created_at      timestamptz NOT NULL DEFAULT now(),
  -- One SKU per dimension combination at any instant.
  EXCLUDE USING gist (
    meter_code WITH =, model WITH =, service_tier WITH =, context_band WITH =,
    tstzrange(effective_from, effective_until, '[)') WITH &&)
);

-- ------------------------------------------------------------------ context-band rules
CREATE TABLE context_band_rule (
  model            text        NOT NULL CHECK (btrim(model) <> ''),
  effective_from   timestamptz NOT NULL,
  effective_until  timestamptz CHECK (effective_until > effective_from),
  threshold_tokens bigint      NOT NULL CHECK (threshold_tokens > 0),
  base_band        text        NOT NULL CHECK (btrim(base_band) <> ''),
  above_band       text        NOT NULL CHECK (btrim(above_band) <> ''),
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (model, effective_from),
  CHECK (base_band <> above_band),
  EXCLUDE USING gist (model WITH =, tstzrange(effective_from, effective_until, '[)') WITH &&)
);

-- ------------------------------------------------------------------ price book
CREATE TABLE list_price (
  sku_id             text        NOT NULL REFERENCES sku (sku_id),
  effective_from     timestamptz NOT NULL CHECK (is_hour(effective_from)),
  effective_until    timestamptz CHECK (effective_until > effective_from AND is_hour(effective_until)),
  price_book_version integer     NOT NULL CHECK (price_book_version > 0),
  created_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (sku_id, effective_from),
  EXCLUDE USING gist (sku_id WITH =, tstzrange(effective_from, effective_until, '[)') WITH &&)
);

-- Flat pricing is one open tier (up_to NULL). Graduated pricing: ascending up_to, last tier open,
-- one per_units for all tiers (checked by libs:rating Pricing when the catalog is loaded).
CREATE TABLE list_price_tier (
  sku_id         text        NOT NULL,
  effective_from timestamptz NOT NULL,
  tier_no        integer     NOT NULL CHECK (tier_no >= 1),
  up_to          bigint      CHECK (up_to > 0),
  rate_cents     bigint      NOT NULL CHECK (rate_cents >= 0),
  per_units      bigint      NOT NULL CHECK (per_units > 0),
  PRIMARY KEY (sku_id, effective_from, tier_no),
  FOREIGN KEY (sku_id, effective_from) REFERENCES list_price (sku_id, effective_from)
);

-- ------------------------------------------------------------------ contract overrides
CREATE TABLE contract_price (
  org_id           text        NOT NULL CHECK (btrim(org_id) <> ''),
  sku_id           text        NOT NULL REFERENCES sku (sku_id),
  effective_from   timestamptz NOT NULL CHECK (is_hour(effective_from)),
  effective_until  timestamptz CHECK (effective_until > effective_from AND is_hour(effective_until)),
  contract_version integer     NOT NULL CHECK (contract_version > 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, sku_id, effective_from),
  EXCLUDE USING gist (
    org_id WITH =, sku_id WITH =, tstzrange(effective_from, effective_until, '[)') WITH &&)
);

CREATE TABLE contract_price_tier (
  org_id         text        NOT NULL,
  sku_id         text        NOT NULL,
  effective_from timestamptz NOT NULL,
  tier_no        integer     NOT NULL CHECK (tier_no >= 1),
  up_to          bigint      CHECK (up_to > 0),
  rate_cents     bigint      NOT NULL CHECK (rate_cents >= 0),
  per_units      bigint      NOT NULL CHECK (per_units > 0),
  PRIMARY KEY (org_id, sku_id, effective_from, tier_no),
  FOREIGN KEY (org_id, sku_id, effective_from)
    REFERENCES contract_price (org_id, sku_id, effective_from)
);

-- ------------------------------------------------------------------ allowance weight book
-- Usage units per token: units / per_tokens (a Cents.Rate counted in usage units).
CREATE TABLE allowance_weight (
  sku_id          text        NOT NULL REFERENCES sku (sku_id),
  effective_from  timestamptz NOT NULL,
  effective_until timestamptz CHECK (effective_until > effective_from),
  units           bigint      NOT NULL CHECK (units >= 0),
  per_tokens      bigint      NOT NULL CHECK (per_tokens > 0),
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (sku_id, effective_from),
  EXCLUDE USING gist (sku_id WITH =, tstzrange(effective_from, effective_until, '[)') WITH &&)
);

-- ------------------------------------------------------------------ triggers
CREATE TRIGGER sku_immutable BEFORE UPDATE OR DELETE ON sku
  FOR EACH ROW EXECUTE FUNCTION reject_edit();
CREATE TRIGGER context_band_rule_immutable BEFORE UPDATE OR DELETE ON context_band_rule
  FOR EACH ROW EXECUTE FUNCTION reject_edit();
CREATE TRIGGER list_price_immutable BEFORE UPDATE OR DELETE ON list_price
  FOR EACH ROW EXECUTE FUNCTION reject_edit();
CREATE TRIGGER contract_price_immutable BEFORE UPDATE OR DELETE ON contract_price
  FOR EACH ROW EXECUTE FUNCTION reject_edit();
CREATE TRIGGER allowance_weight_immutable BEFORE UPDATE OR DELETE ON allowance_weight
  FOR EACH ROW EXECUTE FUNCTION reject_edit();
CREATE TRIGGER list_price_tier_immutable BEFORE UPDATE OR DELETE ON list_price_tier
  FOR EACH ROW EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER contract_price_tier_immutable BEFORE UPDATE OR DELETE ON contract_price_tier
  FOR EACH ROW EXECUTE FUNCTION reject_any_change();

CREATE TRIGGER sku_no_truncate BEFORE TRUNCATE ON sku
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER context_band_rule_no_truncate BEFORE TRUNCATE ON context_band_rule
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER list_price_no_truncate BEFORE TRUNCATE ON list_price
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER list_price_tier_no_truncate BEFORE TRUNCATE ON list_price_tier
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER contract_price_no_truncate BEFORE TRUNCATE ON contract_price
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER contract_price_tier_no_truncate BEFORE TRUNCATE ON contract_price_tier
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER allowance_weight_no_truncate BEFORE TRUNCATE ON allowance_weight
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
