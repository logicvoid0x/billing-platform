-- One schema per service so each owns its Flyway migrations, even on a shared local instance.
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS metering;        -- usage_hourly, applied_batches, usage_hourly_rebuild
CREATE SCHEMA IF NOT EXISTS rating;          -- rated_ledger
CREATE SCHEMA IF NOT EXISTS balance_ledger;
CREATE SCHEMA IF NOT EXISTS budget_shard;    -- usage_windows snapshots
CREATE SCHEMA IF NOT EXISTS reconciliation;
CREATE SCHEMA IF NOT EXISTS invoicing;       -- Stripe pushed state
CREATE SCHEMA IF NOT EXISTS payments;
CREATE SCHEMA IF NOT EXISTS usage_view;
