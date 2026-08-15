-- Home balance ledger (design: "Double-entry, append-only"; "Balance ledger entries" table).
-- An entry is one movement, idempotent on its business key; its postings debit and credit
-- accounts in bigint cents. A deferred constraint trigger checks at commit that every entry
-- balances (debits = credits) and only touches the account kinds its type allows.

CREATE TABLE entry (
  entry_key   text        PRIMARY KEY CHECK (btrim(entry_key) <> ''),
  entry_type  text        NOT NULL CHECK (entry_type IN ('TOP_UP', 'ESCROW_GRANT', 'ESCROW_RETURN',
                                          'USAGE_ROLLUP', 'TRUE_UP', 'GRANT_EXPIRY')),
  org_id      text        NOT NULL CHECK (btrim(org_id) <> ''),
  occurred_at timestamptz NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Account kinds: CASH_CLEARING, CUSTOMER_CREDIT, ESCROW (per region), USAGE_RECOGNIZED,
-- BREAKAGE_REVENUE. account_id = org:kind[:region].
CREATE TABLE posting (
  entry_key    text   NOT NULL REFERENCES entry (entry_key),
  line_no      integer NOT NULL CHECK (line_no >= 1),
  account_id   text   NOT NULL CHECK (btrim(account_id) <> ''),
  account_kind text   NOT NULL CHECK (account_kind IN ('CASH_CLEARING', 'CUSTOMER_CREDIT', 'ESCROW',
                                       'USAGE_RECOGNIZED', 'BREAKAGE_REVENUE')),
  debit_cents  bigint NOT NULL CHECK (debit_cents >= 0),
  credit_cents bigint NOT NULL CHECK (credit_cents >= 0),
  PRIMARY KEY (entry_key, line_no),
  CHECK ((debit_cents > 0) <> (credit_cents > 0)) -- exactly one side per line
);
CREATE INDEX posting_account ON posting (account_id);

-- Checked at commit, once per entry: it has postings, they balance, and they use the account
-- kinds of the entry's type (TRUE_UP may go either way between credit and usage).
CREATE FUNCTION check_entry_balances() RETURNS trigger
  LANGUAGE plpgsql AS $$
DECLARE
  e        balance_ledger.entry%ROWTYPE;
  debits   bigint;
  credits  bigint;
  dr_kinds text[];
  cr_kinds text[];
BEGIN
  SELECT * INTO e FROM balance_ledger.entry WHERE entry_key = NEW.entry_key;
  SELECT coalesce(sum(debit_cents), 0), coalesce(sum(credit_cents), 0),
         coalesce(array_agg(DISTINCT account_kind) FILTER (WHERE debit_cents > 0), '{}'),
         coalesce(array_agg(DISTINCT account_kind) FILTER (WHERE credit_cents > 0), '{}')
    INTO debits, credits, dr_kinds, cr_kinds
    FROM balance_ledger.posting WHERE entry_key = NEW.entry_key;
  IF debits = 0 OR debits <> credits THEN
    RAISE EXCEPTION 'entry % does not balance: debits % credits %', NEW.entry_key, debits, credits
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NOT (
       (e.entry_type = 'TOP_UP'        AND dr_kinds = '{CASH_CLEARING}'   AND cr_kinds = '{CUSTOMER_CREDIT}')
    OR (e.entry_type = 'ESCROW_GRANT'  AND dr_kinds = '{CUSTOMER_CREDIT}' AND cr_kinds = '{ESCROW}')
    OR (e.entry_type = 'ESCROW_RETURN' AND dr_kinds = '{ESCROW}'          AND cr_kinds = '{CUSTOMER_CREDIT}')
    OR (e.entry_type = 'USAGE_ROLLUP'  AND dr_kinds = '{ESCROW}'          AND cr_kinds = '{USAGE_RECOGNIZED}')
    OR (e.entry_type = 'TRUE_UP'       AND ((dr_kinds = '{CUSTOMER_CREDIT}' AND cr_kinds = '{USAGE_RECOGNIZED}')
                                         OR (dr_kinds = '{USAGE_RECOGNIZED}' AND cr_kinds = '{CUSTOMER_CREDIT}')))
    OR (e.entry_type = 'GRANT_EXPIRY'  AND dr_kinds = '{CUSTOMER_CREDIT}' AND cr_kinds = '{BREAKAGE_REVENUE}')
  ) THEN
    RAISE EXCEPTION 'entry % (%) debits % and credits %, which its type does not allow',
      NEW.entry_key, e.entry_type, dr_kinds, cr_kinds
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER posting_balances AFTER INSERT ON posting
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balances();

-- An entry without postings is caught too: the entry row's own deferred check.
CREATE FUNCTION check_entry_has_postings() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM balance_ledger.posting WHERE entry_key = NEW.entry_key) THEN
    RAISE EXCEPTION 'entry % has no postings', NEW.entry_key
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER entry_has_postings AFTER INSERT ON entry
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_has_postings();

-- Append-only: corrections are new entries.
CREATE FUNCTION append_only() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the balance ledger is append-only; post a new entry instead'
    USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER entry_append_only BEFORE UPDATE OR DELETE ON entry
  FOR EACH ROW EXECUTE FUNCTION append_only();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON posting
  FOR EACH ROW EXECUTE FUNCTION append_only();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON entry
  FOR EACH STATEMENT EXECUTE FUNCTION append_only();
CREATE TRIGGER posting_no_truncate BEFORE TRUNCATE ON posting
  FOR EACH STATEMENT EXECUTE FUNCTION append_only();
