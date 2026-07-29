-- Replay target (P6.2): jobs:usage-replay recomputes sealed hours from the raw archive into this
-- table, then swaps them into usage_hourly with delete-and-insert in one transaction.

CREATE TABLE usage_hourly_rebuild (
  org_id     text        NOT NULL CHECK (btrim(org_id) <> ''),
  sku_id     text        NOT NULL CHECK (btrim(sku_id) <> ''),
  event_hour timestamptz NOT NULL CHECK (mod(extract(epoch FROM event_hour), 3600) = 0),
  quantity   bigint      NOT NULL CHECK (quantity >= 0),
  lines      bigint      NOT NULL CHECK (lines >= 0),
  replay_id  text        NOT NULL CHECK (btrim(replay_id) <> ''),
  PRIMARY KEY (org_id, sku_id, event_hour)
);
