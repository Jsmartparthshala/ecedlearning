-- JSP ECED Learning — an audit trail for the ops console
--
-- Safe to run at any time. It adds one table and touches nothing that exists,
-- so the console and the televisions behave identically before and after.
--
-- Why this exists now
--
-- The console authenticates with a single shared passcode (OPS_PASSCODE) and
-- acts through the service_role key, which bypasses row level security
-- entirely. That was proportionate while the console could only activate and
-- revoke televisions: every one of those actions is visible in the devices
-- table afterwards, and reversible by hand.
--
-- It stops being proportionate the moment the console can rewrite lesson titles
-- and publish legal text to every classroom. Those actions leave no trace in
-- the row they change - a title is simply a different title afterwards - and
-- there is no per-operator identity anywhere in the product to ask. So when a
-- title is wrong on Monday there is currently no way to learn what it was on
-- Friday, or who changed it.
--
-- This does not add identity. A shared passcode cannot tell two operators
-- apart, and pretending otherwise would be worse than not logging. What it adds
-- is the before value, the after value and the time, which is what actually
-- gets a mistake undone.

create table if not exists ops_audit (
  id          bigserial primary key,

  -- The console action name, exactly as the function dispatches it:
  -- 'activate', 'revoke', 'rename-lesson', 'save-document', 'set-expiry'.
  action      text not null,

  -- What was acted on. Free text rather than a foreign key on purpose: this
  -- table has to outlive the row it describes, and half the value of an audit
  -- entry is being able to read it after the thing is gone.
  target      text,

  -- Enough of the change to undo it. `before` is what the row held, `after` is
  -- what it holds now. Both jsonb, both nullable - a create has no before and a
  -- delete has no after.
  before      jsonb,
  after       jsonb,

  -- Everything the request can honestly say about who did it, which today is
  -- the client IP and the browser's user agent. Not identity. A label on a
  -- shared credential, useful for telling an office laptop apart from a phone
  -- on the far side of the country.
  actor_hint  text,

  at          timestamptz not null default now()
);

create index if not exists ops_audit_at_idx on ops_audit (at desc);
create index if not exists ops_audit_action_idx on ops_audit (action, at desc);

-- No policy at all, deliberately. Under RLS that means no anon or authenticated
-- caller can read or write this table by any route - only the service_role key
-- held by the Netlify function, which is the only thing that should ever write
-- an audit row and the only thing that should ever read one back.
alter table ops_audit enable row level security;

revoke all on ops_audit from anon, authenticated;
revoke all on sequence ops_audit_id_seq from anon, authenticated;

notify pgrst, 'reload schema';
