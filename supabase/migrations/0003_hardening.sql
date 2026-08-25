-- JSP ECED Learning — security hardening
--
-- ############################################################################
-- #  DO NOT RUN THIS BEFORE THE DEMO.                                        #
-- #                                                                          #
-- #  It changes how a TV fetches its session token, and requires the         #
-- #  matching Kotlin change below. Applying it without that change breaks     #
-- #  pairing — which is the demo's opening beat.                             #
-- #                                                                          #
-- #  Run it the day AFTER the demo, together with the Kotlin edit.           #
-- ############################################################################
--
-- What it fixes: 0002_security.sql lets any anon client read every unrevoked session
-- row. Since the anon key ships inside the APK, that means any TV — or anyone
-- who unpacks the APK — can read every school's token. Fine for one device on a
-- desk; not fine in 753 municipalities.

-- ---------------------------------------------------------------- the RPC

-- SECURITY DEFINER so it can read sessions while the caller cannot.
-- It returns at most one row, and only for the hardware_uuid asked for.
create or replace function public.claim_session(p_hardware_uuid text)
returns table (
  id          uuid,
  device_id   uuid,
  token       text,
  expires_at  timestamptz,
  revoked     boolean
)
language sql
security definer
set search_path = public
as $$
  select s.id, s.device_id, s.token, s.expires_at, s.revoked
  from sessions s
  join devices d on d.id = s.device_id
  where d.hardware_uuid = p_hardware_uuid
    and s.revoked = false
  order by s.issued_at desc
  limit 1;
$$;

revoke all on function public.claim_session(text) from public;
grant execute on function public.claim_session(text) to anon, authenticated;

-- --------------------------------------------------- drop the blanket read

drop policy if exists sessions_read on sessions;

-- Sessions are now reachable ONLY through claim_session(). Direct selects by
-- anon return nothing, because RLS is on and no SELECT policy remains.

-- ------------------------------------------------------- quiz answer leak

-- 002 leaves quiz_options.is_correct readable by anon, so the answers are one
-- API call away. Grade server-side instead.
create or replace function public.grade_quiz(p_quiz_id uuid, p_answers jsonb)
returns table (score int, total int)
language sql
security definer
set search_path = public
as $$
  select
    count(*) filter (
      where o.is_correct
        and o.id::text = p_answers ->> q.id::text
    )::int as score,
    count(*)::int as total
  from quiz_questions q
  join quiz_options  o on o.question_id = q.id
  where q.quiz_id = p_quiz_id;
$$;

revoke all on function public.grade_quiz(uuid, jsonb) from public;
grant execute on function public.grade_quiz(uuid, jsonb) to anon, authenticated;

drop policy if exists catalog_read_options on quiz_options;

-- Options still need to be *displayed*, just without the answer key.
create or replace view quiz_options_public
  with (security_invoker = false) as
  select id, question_id, label, sort_order from quiz_options;

grant select on quiz_options_public to anon, authenticated;

-- ############################################################################
-- #  MATCHING KOTLIN CHANGE — required, or pairing breaks                    #
-- ############################################################################
--
-- In core/src/main/kotlin/np/com/jagdamba/eced/core/data/DeviceRepository.kt,
-- replace the body of fetchSession() with an RPC call:
--
--     suspend fun fetchSession(deviceId: String): SessionRow? =
--         withContext(Dispatchers.IO) {
--             runCatching {
--                 client.postgrest
--                     .rpc("claim_session", buildJsonObject {
--                         put("p_hardware_uuid", hardwareUuid())
--                     })
--                     .decodeList<SessionRow>()
--                     .firstOrNull()
--             }.getOrNull()
--         }
--
-- Note it keys off hardwareUuid() rather than deviceId — the whole point is
-- that the client proves which device it is, instead of asking for any row.
-- Keep the `deviceId` parameter or drop it; PairingFragment passes it today.
