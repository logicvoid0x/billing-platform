-- Credit grants (P9.3; design: "sub-balances with an expiry, drawn down expiring-first; expiry
-- posts GRANT_EXPIRY to breakage revenue"). A grant is funded by a TOP_UP entry keyed
-- grant:<grant_id>, and expires through a GRANT_EXPIRY entry keyed grant:<grant_id>:expiry. Which
-- grant a debit draws is derived from the ledger in time order (CreditGrants), not stored.

CREATE TABLE credit_grant (
  grant_id          text        PRIMARY KEY CHECK (btrim(grant_id) <> ''),
  org_id            text        NOT NULL CHECK (btrim(org_id) <> ''),
  cents             bigint      NOT NULL CHECK (cents > 0),
  granted_at        timestamptz NOT NULL,
  expires_at        timestamptz NOT NULL,
  source            text        NOT NULL CHECK (source IN ('PROMOTIONAL', 'PURCHASED')),
  funding_entry_key text        NOT NULL UNIQUE REFERENCES entry (entry_key),
  CHECK (expires_at > granted_at)
);
CREATE INDEX credit_grant_org ON credit_grant (org_id, expires_at);

CREATE TRIGGER credit_grant_append_only BEFORE UPDATE OR DELETE ON credit_grant
  FOR EACH ROW EXECUTE FUNCTION append_only();
