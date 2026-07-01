-- Model registry: which meters each model emits in each service tier. The launch gate checks that
-- every registered (model, service tier, meter) resolves to a priced SKU in every context band the
-- model can produce. Rows are insert-only, like the rest of the catalog.

CREATE TABLE model_offering (
  model         text        NOT NULL CHECK (btrim(model) <> ''),
  service_tier  text        NOT NULL CHECK (btrim(service_tier) <> ''),
  meter_code    text        NOT NULL CHECK (meter_code ~ '^[a-z][a-z0-9_]{0,63}$'),
  registered_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (model, service_tier, meter_code)
);

CREATE TRIGGER model_offering_immutable BEFORE UPDATE OR DELETE ON model_offering
  FOR EACH ROW EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER model_offering_no_truncate BEFORE TRUNCATE ON model_offering
  FOR EACH STATEMENT EXECUTE FUNCTION reject_any_change();
CREATE TRIGGER model_offering_version AFTER INSERT ON model_offering
  FOR EACH ROW EXECUTE FUNCTION bump_version();
