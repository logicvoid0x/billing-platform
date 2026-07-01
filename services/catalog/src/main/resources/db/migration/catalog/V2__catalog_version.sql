-- Catalog version: one counter bumped by every catalog row written (row triggers, so a no-op
-- ON CONFLICT DO NOTHING leaves it alone). A snapshot records the version it was read at (rated
-- rows carry it), and caches use it to notice changes.

CREATE TABLE catalog_version (
  id      boolean PRIMARY KEY DEFAULT true CHECK (id),
  version bigint  NOT NULL CHECK (version >= 1)
);
INSERT INTO catalog_version (id, version) VALUES (true, 1);

CREATE FUNCTION bump_version() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  UPDATE catalog.catalog_version SET version = version + 1 WHERE id;
  RETURN NULL;
END $$;

CREATE TRIGGER sku_version AFTER INSERT OR UPDATE ON sku
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER context_band_rule_version AFTER INSERT OR UPDATE ON context_band_rule
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER list_price_version AFTER INSERT OR UPDATE ON list_price
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER list_price_tier_version AFTER INSERT ON list_price_tier
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER contract_price_version AFTER INSERT OR UPDATE ON contract_price
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER contract_price_tier_version AFTER INSERT ON contract_price_tier
  FOR EACH ROW EXECUTE FUNCTION bump_version();
CREATE TRIGGER allowance_weight_version AFTER INSERT OR UPDATE ON allowance_weight
  FOR EACH ROW EXECUTE FUNCTION bump_version();

-- The counter itself only moves forward.
CREATE FUNCTION version_forward_only() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.version <= OLD.version THEN
    RAISE EXCEPTION 'catalog version only moves forward' USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER catalog_version_forward BEFORE UPDATE ON catalog_version
  FOR EACH ROW EXECUTE FUNCTION version_forward_only();
CREATE TRIGGER catalog_version_no_delete BEFORE DELETE OR TRUNCATE ON catalog_version
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
