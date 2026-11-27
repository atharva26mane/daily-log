-- Daily Log Snippets. Run this ONCE in Supabase > SQL Editor > New query > Run.
-- Run leaderboard.sql first (Snippets use the same groups). Safe to run again.

create table if not exists sn_snippets (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references lb_groups(id) on delete cascade,
  sender uuid not null references auth.users(id) on delete cascade,
  img text not null,
  stamp text not null default '',
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '24 hours'
);
create index if not exists sn_snippets_group_idx on sn_snippets (group_id, created_at desc);
create index if not exists sn_snippets_exp_idx on sn_snippets (expires_at);

create table if not exists sn_views (
  snippet_id uuid not null references sn_snippets(id) on delete cascade,
  viewer uuid not null references auth.users(id) on delete cascade,
  viewed_at timestamptz not null default now(),
  primary key (snippet_id, viewer)
);
create table if not exists sn_reacts (
  snippet_id uuid not null references sn_snippets(id) on delete cascade,
  viewer uuid not null references auth.users(id) on delete cascade,
  kind text not null default 'none' check (kind in ('proud','same','join','drink','none')),
  note text not null default '' check (char_length(note) <= 24),
  updated_at timestamptz not null default now(),
  primary key (snippet_id, viewer)
);

-- nobody reads these tables directly; everything goes through the functions below
alter table sn_snippets enable row level security;
alter table sn_views enable row level security;
alter table sn_reacts enable row level security;
revoke all on sn_snippets, sn_views, sn_reacts from anon, authenticated;

-- the list (no photos in here, photos come only from sn_open)
drop function if exists sn_feed();
create or replace function sn_feed()
returns table(id uuid, group_id uuid, group_name text, sender_name text, stamp text, created_at timestamptz, expires_at timestamptz,
              mine boolean, seen boolean, views int, reacts int, last_react timestamptz, my_kind text, my_note text)
language sql security definer set search_path = public as $fn$
  select s.id, s.group_id, g.name, lb_name(s.sender, m.nickname, m.anonymous), s.stamp, s.created_at, s.expires_at,
         s.sender = auth.uid(),
         exists (select 1 from sn_views v where v.snippet_id = s.id and v.viewer = auth.uid()),
         (select count(*)::int from sn_views v where v.snippet_id = s.id),
         (select count(*)::int from sn_reacts r where r.snippet_id = s.id),
         (select max(r.updated_at) from sn_reacts r where r.snippet_id = s.id),
         coalesce((select r.kind from sn_reacts r where r.snippet_id = s.id and r.viewer = auth.uid()), ''),
         coalesce((select r.note from sn_reacts r where r.snippet_id = s.id and r.viewer = auth.uid()), '')
  from sn_snippets s
  join lb_groups g on g.id = s.group_id
  join lb_group_members gm on gm.group_id = s.group_id and gm.user_id = auth.uid()
  left join lb_members m on m.user_id = s.sender
  where s.expires_at > now() and is_approved()
  order by s.created_at desc
  limit 60;
$fn$;

create or replace function sn_send(p_group uuid, p_img text, p_stamp text) returns uuid
language plpgsql security definer set search_path = public as $fn$
declare nid uuid;
begin
  if auth.uid() is null or not is_approved() then raise exception 'not allowed'; end if;
  if not exists (select 1 from lb_group_members where group_id = p_group and user_id = auth.uid()) then raise exception 'you are not in that group'; end if;
  if p_img is null or p_img not like 'data:image/jpeg;base64,%' or char_length(p_img) > 260000 then raise exception 'photo is too big'; end if;
  if (select count(*) from sn_snippets where sender = auth.uid() and created_at > now() - interval '24 hours') >= 12 then raise exception 'You can send 12 snippets a day'; end if;
  insert into sn_snippets(group_id, sender, img, stamp) values (p_group, auth.uid(), p_img, left(btrim(coalesce(p_stamp, '')), 60)) returning sn_snippets.id into nid;
  return nid;
end $fn$;

-- opening: a friend gets the photo ONE time. The sender can always look at their own.
create or replace function sn_open(p_id uuid) returns text
language plpgsql security definer set search_path = public as $fn$
declare s sn_snippets%rowtype; got int;
begin
  if auth.uid() is null or not is_approved() then return null; end if;
  select * into s from sn_snippets where id = p_id and expires_at > now();
  if not found then return null; end if;
  if not exists (select 1 from lb_group_members where group_id = s.group_id and user_id = auth.uid()) then return null; end if;
  if s.sender = auth.uid() then return s.img; end if;
  insert into sn_views(snippet_id, viewer) values (p_id, auth.uid()) on conflict do nothing;
  get diagnostics got = row_count;
  if got = 0 then return null; end if;
  return s.img;
end $fn$;

create or replace function sn_react(p_id uuid, p_kind text, p_note text) returns void
language plpgsql security definer set search_path = public as $fn$
declare n text := left(btrim(coalesce(p_note, '')), 24);
begin
  if p_kind is null or p_kind not in ('proud','same','join','drink','none') then raise exception 'bad reaction'; end if;
  if not exists (select 1 from sn_views v join sn_snippets s on s.id = v.snippet_id where v.snippet_id = p_id and v.viewer = auth.uid() and s.expires_at > now()) then raise exception 'open it first'; end if;
  if p_kind = 'none' and n = '' then return; end if;
  insert into sn_reacts(snippet_id, viewer, kind, note) values (p_id, auth.uid(), p_kind, n)
  on conflict (snippet_id, viewer) do update set kind = excluded.kind, note = excluded.note, updated_at = now();
end $fn$;

-- the sender sees who opened it and what they stamped
create or replace function sn_who(p_id uuid)
returns table(name text, viewed boolean, viewed_at timestamptz, kind text, note text)
language sql security definer set search_path = public as $fn$
  select lb_name(gm.user_id, m.nickname, m.anonymous), v.viewer is not null, v.viewed_at, coalesce(r.kind, ''), coalesce(r.note, '')
  from sn_snippets s
  join lb_group_members gm on gm.group_id = s.group_id and gm.user_id <> s.sender
  left join lb_members m on m.user_id = gm.user_id
  left join sn_views v on v.snippet_id = s.id and v.viewer = gm.user_id
  left join sn_reacts r on r.snippet_id = s.id and r.viewer = gm.user_id
  where s.id = p_id and s.sender = auth.uid()
  order by v.viewed_at nulls last, 1;
$fn$;

create or replace function sn_delete(p_id uuid) returns void language sql security definer set search_path = public as $fn$
  delete from sn_snippets where id = p_id and sender = auth.uid();
$fn$;

-- housekeeping: remove old ones (also hidden by the 24 hour limit even if this never runs)
create or replace function sn_cleanup() returns void language sql security definer set search_path = public as $fn$
  delete from sn_snippets where expires_at < now() - interval '1 hour';
$fn$;
revoke execute on function sn_cleanup() from public, anon, authenticated;

grant execute on function sn_feed(), sn_send(uuid, text, text), sn_open(uuid), sn_react(uuid, text, text), sn_who(uuid), sn_delete(uuid) to authenticated;

do $c$ begin
  perform cron.unschedule('sn-clean');
exception when others then null;
end $c$;
do $c$ begin
  perform cron.schedule('sn-clean', '*/30 * * * *', 'select sn_cleanup()');
exception when others then raise notice 'pg_cron not available, old snippets are still hidden after 24 hours';
end $c$;
