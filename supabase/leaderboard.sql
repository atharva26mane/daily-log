-- Daily Log leaderboard. Run this ONCE in Supabase > SQL Editor > New query > Run.
-- Safe to run again.

create table if not exists lb_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  nickname text not null check (char_length(nickname) between 2 and 20),
  anonymous boolean not null default false,
  in_global boolean not null default true,
  created_at timestamptz not null default now()
);
create table if not exists lb_scores (
  user_id uuid not null references auth.users(id) on delete cascade,
  week date not null,
  points int not null default 0,
  daily int[] not null default '{}',
  perfect int not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, week)
);
create table if not exists lb_groups (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 2 and 24),
  code text unique not null,
  owner uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create table if not exists lb_group_members (
  group_id uuid not null references lb_groups(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);

alter table lb_members enable row level security;
alter table lb_scores enable row level security;
alter table lb_groups enable row level security;
alter table lb_group_members enable row level security;

drop policy if exists lb_members_own on lb_members;
create policy lb_members_own on lb_members for all using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists lb_members_admin on lb_members;
create policy lb_members_admin on lb_members for delete using (is_admin());
drop policy if exists lb_scores_own on lb_scores;
create policy lb_scores_own on lb_scores for all using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists lb_scores_admin on lb_scores;
create policy lb_scores_admin on lb_scores for delete using (is_admin());
drop policy if exists lb_gm_own on lb_group_members;
create policy lb_gm_own on lb_group_members for select using (user_id = auth.uid());

-- who am I on the board
create or replace function lb_me() returns json language sql security definer set search_path = public as $fn$
  select coalesce((select json_build_object('member', true, 'nickname', nickname, 'anonymous', anonymous, 'in_global', in_global)
                   from lb_members where user_id = auth.uid()), json_build_object('member', false));
$fn$;

create or replace function lb_join(p_nick text, p_anon boolean, p_global boolean) returns void
language plpgsql security definer set search_path = public as $fn$
begin
  if not is_approved() then raise exception 'not approved'; end if;
  insert into lb_members(user_id, nickname, anonymous, in_global)
  values (auth.uid(), btrim(p_nick), coalesce(p_anon, false), coalesce(p_global, true))
  on conflict (user_id) do update set nickname = excluded.nickname, anonymous = excluded.anonymous, in_global = excluded.in_global;
end $fn$;

create or replace function lb_leave() returns void language plpgsql security definer set search_path = public as $fn$
begin
  delete from lb_group_members where user_id = auth.uid();
  delete from lb_scores where user_id = auth.uid();
  delete from lb_members where user_id = auth.uid();
end $fn$;

create or replace function lb_submit(p_week date, p_points int, p_daily int[], p_perfect int) returns void
language plpgsql security definer set search_path = public as $fn$
begin
  if not exists (select 1 from lb_members where user_id = auth.uid()) then return; end if;
  insert into lb_scores(user_id, week, points, daily, perfect, updated_at)
  values (auth.uid(), p_week, least(700, greatest(0, p_points)), coalesce(p_daily, '{}'), least(7, greatest(0, p_perfect)), now())
  on conflict (user_id, week) do update set points = excluded.points, daily = excluded.daily, perfect = excluded.perfect, updated_at = now();
end $fn$;

create or replace function lb_name(p_user uuid, p_nick text, p_anon boolean) returns text language sql immutable as $fn$
  select case when p_anon then 'Anonymous ' || (array['Fox','Owl','Otter','Panda','Heron','Lynx','Koala','Gecko','Falcon','Yak'])[1 + (get_byte(decode(md5(p_user::text), 'hex'), 0) % 10)]
              else p_nick end;
$fn$;

create or replace function lb_global(p_week date)
returns table(rank int, name text, anon boolean, points int, daily int[], perfect int, is_me boolean)
language sql security definer set search_path = public as $fn$
  select * from (
    select (rank() over (order by s.points desc, s.perfect desc))::int as rank,
           lb_name(m.user_id, m.nickname, m.anonymous) as name, m.anonymous as anon, s.points as points, s.daily as daily, s.perfect as perfect, (m.user_id = auth.uid()) as is_me
    from lb_members m join lb_scores s on s.user_id = m.user_id and s.week = p_week
    where is_approved() and (m.in_global or m.user_id = auth.uid())
  ) t where t.rank <= 50 or t.is_me order by t.rank, t.name;
$fn$;

create or replace function lb_group_board(p_group uuid, p_week date)
returns table(rank int, name text, anon boolean, points int, daily int[], perfect int, is_me boolean)
language sql security definer set search_path = public as $fn$
  select (rank() over (order by coalesce(s.points, 0) desc, coalesce(s.perfect, 0) desc))::int,
         lb_name(m.user_id, m.nickname, m.anonymous), m.anonymous, coalesce(s.points, 0), coalesce(s.daily, '{}'), coalesce(s.perfect, 0), (m.user_id = auth.uid())
  from lb_group_members g
  join lb_members m on m.user_id = g.user_id
  left join lb_scores s on s.user_id = g.user_id and s.week = p_week
  where g.group_id = p_group
    and exists (select 1 from lb_group_members me where me.group_id = p_group and me.user_id = auth.uid())
  order by 1, 2;
$fn$;

create or replace function lb_my_groups() returns table(id uuid, name text, code text, members int)
language sql security definer set search_path = public as $fn$
  select g.id, g.name, g.code, (select count(*)::int from lb_group_members x where x.group_id = g.id)
  from lb_groups g join lb_group_members gm on gm.group_id = g.id and gm.user_id = auth.uid()
  order by g.created_at;
$fn$;

create or replace function lb_create_group(p_name text) returns table(id uuid, name text, code text)
language plpgsql security definer set search_path = public as $fn$
declare c text; gid uuid; alpha text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
begin
  if not exists (select 1 from lb_members where user_id = auth.uid()) then raise exception 'join the leaderboard first'; end if;
  if (select count(*) from lb_groups where owner = auth.uid()) >= 5 then raise exception 'you can create up to 5 groups'; end if;
  loop
    c := '';
    for i in 1..6 loop c := c || substr(alpha, 1 + floor(random() * length(alpha))::int, 1); end loop;
    exit when not exists (select 1 from lb_groups where lb_groups.code = c);
  end loop;
  insert into lb_groups(name, code, owner) values (btrim(p_name), c, auth.uid()) returning lb_groups.id into gid;
  insert into lb_group_members(group_id, user_id) values (gid, auth.uid());
  return query select g.id, g.name, g.code from lb_groups g where g.id = gid;
end $fn$;

create or replace function lb_join_group(p_code text) returns table(id uuid, name text, code text)
language plpgsql security definer set search_path = public as $fn$
declare gid uuid;
begin
  if not exists (select 1 from lb_members where user_id = auth.uid()) then raise exception 'join the leaderboard first'; end if;
  select g.id into gid from lb_groups g where g.code = upper(btrim(p_code));
  if gid is null then raise exception 'no group with that code'; end if;
  if (select count(*) from lb_group_members where group_id = gid) >= 50 then raise exception 'that group is full'; end if;
  insert into lb_group_members(group_id, user_id) values (gid, auth.uid()) on conflict do nothing;
  return query select g.id, g.name, g.code from lb_groups g where g.id = gid;
end $fn$;

create or replace function lb_leave_group(p_group uuid) returns void language sql security definer set search_path = public as $fn$
  delete from lb_group_members where group_id = p_group and user_id = auth.uid();
$fn$;

-- show or hide me on the global board only (groups and scores stay)
create or replace function lb_set_global(p_global boolean) returns void language sql security definer set search_path = public as $fn$
  update lb_members set in_global = coalesce(p_global, true) where user_id = auth.uid();
$fn$;

-- preview a group from an invite link, before joining
create or replace function lb_group_peek(p_code text)
returns table(id uuid, name text, code text, members int, inviter text, joined boolean, is_full boolean)
language sql security definer set search_path = public as $fn$
  select g.id, g.name, g.code,
         (select count(*)::int from lb_group_members x where x.group_id = g.id),
         coalesce((select lb_name(o.user_id, o.nickname, o.anonymous) from lb_members o where o.user_id = g.owner), 'A friend'),
         exists (select 1 from lb_group_members me where me.group_id = g.id and me.user_id = auth.uid()),
         (select count(*) from lb_group_members x where x.group_id = g.id) >= 50
  from lb_groups g where g.code = upper(btrim(p_code)) and is_approved();
$fn$;

grant execute on function lb_me(), lb_join(text, boolean, boolean), lb_leave(), lb_submit(date, int, int[], int),
  lb_global(date), lb_group_board(uuid, date), lb_my_groups(), lb_create_group(text), lb_join_group(text), lb_leave_group(uuid), lb_set_global(boolean), lb_group_peek(text) to authenticated;
