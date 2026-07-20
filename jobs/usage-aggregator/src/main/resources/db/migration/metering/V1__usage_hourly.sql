-- Metering: hourly usage per (org, SKU, event hour), written by the Flink aggregator in
-- checkpoint-aligned batches. A batch's increments and its applied_batches row commit in one
-- transaction, so a batch is applied exactly once even when Flink re-commits it after recovery.

CREATE TABLE usage_hourly (
  org_id     text        NOT NULL CHECK (btrim(org_id) <> ''),
  sku_id     text        NOT NULL CHECK (btrim(sku_id) <> ''),
  event_hour timestamptz NOT NULL CHECK (mod(extract(epoch FROM event_hour), 3600) = 0),
  quantity   bigint      NOT NULL CHECK (quantity >= 0),
  lines      bigint      NOT NULL CHECK (lines >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, sku_id, event_hour)
);

-- One row per applied batch: the idempotency key of the sink (batch id = job:jobId:subtask:barrier).
CREATE TABLE applied_batches (
  batch_id   text        PRIMARY KEY CHECK (btrim(batch_id) <> ''),
  increments integer     NOT NULL CHECK (increments >= 0),
  applied_at timestamptz NOT NULL DEFAULT now()
);
