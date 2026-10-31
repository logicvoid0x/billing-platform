-- Hourly usage with workspace and API key, for the usage view only (open question Q12, option b).
-- Written by the same checkpointed sink as usage_hourly, in the same transaction, so summing it over
-- workspace and key equals usage_hourly. Rating and invoicing never read it.

CREATE TABLE usage_hourly_by_key (
  org_id       text        NOT NULL CHECK (btrim(org_id) <> ''),
  workspace_id text        NOT NULL CHECK (btrim(workspace_id) <> ''),
  api_key_id   text        NOT NULL CHECK (btrim(api_key_id) <> ''),
  sku_id       text        NOT NULL CHECK (btrim(sku_id) <> ''),
  event_hour   timestamptz NOT NULL CHECK (mod(extract(epoch FROM event_hour), 3600) = 0),
  quantity     bigint      NOT NULL CHECK (quantity >= 0),
  lines        bigint      NOT NULL CHECK (lines >= 0),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, workspace_id, api_key_id, sku_id, event_hour)
);

-- The usage view reads one org's hours.
CREATE INDEX usage_hourly_by_key_org_hour ON usage_hourly_by_key (org_id, event_hour);
