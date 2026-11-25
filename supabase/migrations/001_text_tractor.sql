-- Get Down Text Tractor backend (Supabase / Postgres).
-- All objects are prefixed tt_ so this can live in its own project or alongside other apps.
-- Clients never touch tables directly: RLS is on with no policies, everything goes through
-- SECURITY DEFINER functions that validate input. Copied TEXT is never sent to the server,
-- only counts, lengths and the source app's package name.

create table if not exists public.tt_devices (
  device_id   text primary key check (char_length(device_id) between 8 and 64),
  user_id     uuid references auth.users(id) on delete set null,
  model       text,
  android     int,
  app_version text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now()
);

create table if not exists public.tt_events (
  id          bigserial primary key,
  device_id   text not null,
  user_id     uuid references auth.users(id) on delete set null,
  type        text not null check (type in ('capture', 'copy')),
  chars       int not null default 0,
  source      text,
  method      text,
  created_at  timestamptz not null default now()
);
create index if not exists tt_events_created_idx on public.tt_events (created_at);
create index if not exists tt_events_device_idx  on public.tt_events (device_id);
create index if not exists tt_events_user_idx    on public.tt_events (user_id);

create table if not exists public.tt_admins (
  email text primary key
);

create table if not exists public.tt_settings (
  id           int primary key default 1 check (id = 1),
  auth_mode    text not null default 'optional' check (auth_mode in ('optional', 'required', 'off')),
  announcement text not null default '',
  updated_at   timestamptz not null default now()
);
insert into public.tt_settings (id) values (1) on conflict do nothing;
insert into public.tt_admins (email) values ('contact@getdownaf.info') on conflict do nothing;

alter table public.tt_devices  enable row level security;
alter table public.tt_events   enable row level security;
alter table public.tt_admins   enable row level security;
alter table public.tt_settings enable row level security;

-- ---------- helpers ----------

create or replace function public.tt_is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1
    from public.tt_admins a
    join auth.users u on lower(u.email) = lower(a.email)
    where u.id = auth.uid() and u.email_confirmed_at is not null
  );
$$;

create or replace function public.tt_require_admin()
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if not public.tt_is_admin() then
    raise exception 'Not an admin' using errcode = '42501';
  end if;
end $$;

-- ---------- app-facing (anon + authenticated) ----------

create or replace function public.tt_get_config()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object('auth_mode', auth_mode, 'announcement', announcement)
  from public.tt_settings where id = 1;
$$;

create or replace function public.tt_ping(p_device_id text, p_model text, p_android int, p_app_version text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_device_id is null or char_length(p_device_id) not between 8 and 64 then return; end if;
  insert into public.tt_devices (device_id, user_id, model, android, app_version)
  values (p_device_id, auth.uid(), left(p_model, 80), p_android, left(p_app_version, 20))
  on conflict (device_id) do update set
    user_id     = coalesce(auth.uid(), public.tt_devices.user_id),
    model       = excluded.model,
    android     = excluded.android,
    app_version = excluded.app_version,
    last_seen   = now();
end $$;

create or replace function public.tt_log_events(p_device_id text, p_events jsonb)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if p_device_id is null or char_length(p_device_id) not between 8 and 64 then return 0; end if;
  if jsonb_typeof(p_events) <> 'array' then return 0; end if;

  insert into public.tt_devices (device_id, user_id) values (p_device_id, auth.uid())
  on conflict (device_id) do update set
    last_seen = now(),
    user_id   = coalesce(auth.uid(), public.tt_devices.user_id);

  insert into public.tt_events (device_id, user_id, type, chars, source, method, created_at)
  select p_device_id, auth.uid(), e->>'type',
         greatest(0, least(coalesce((e->>'chars')::int, 0), 1000000)),
         left(e->>'source', 120), left(e->>'method', 20),
         -- trust the phone's timestamp (offline queue) but never the future or > 30 days back
         least(now(), greatest(now() - interval '30 days',
               coalesce((e->>'at')::timestamptz, now())))
  from (select value as e from jsonb_array_elements(p_events) limit 200) x
  where e->>'type' in ('capture', 'copy');
  get diagnostics n = row_count;
  return n;
end $$;

-- ---------- admin dashboard ----------

create or replace function public.tt_admin_overview()
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  today timestamptz := date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata';
  r jsonb;
begin
  perform public.tt_require_admin();
  select jsonb_build_object(
    'total_devices',     (select count(*) from tt_devices),
    'registered_users',  (select count(distinct user_id) from tt_devices where user_id is not null),
    'guest_devices',     (select count(*) from tt_devices where user_id is null),
    'active_today',      (select count(*) from tt_devices where last_seen >= today),
    'active_7d',         (select count(*) from tt_devices where last_seen >= now() - interval '7 days'),
    'active_30d',        (select count(*) from tt_devices where last_seen >= now() - interval '30 days'),
    'new_today',         (select count(*) from tt_devices where first_seen >= today),
    'captures_total',    (select count(*) from tt_events where type = 'capture'),
    'copies_total',      (select count(*) from tt_events where type = 'copy'),
    'copies_today',      (select count(*) from tt_events where type = 'copy' and created_at >= today),
    'chars_copied',      (select coalesce(sum(chars), 0) from tt_events where type = 'copy'),
    'auth_mode',         (select auth_mode from tt_settings where id = 1),
    'announcement',      (select announcement from tt_settings where id = 1)
  ) into r;
  return r;
end $$;

create or replace function public.tt_admin_daily(p_days int default 30)
returns table (day date, active_devices bigint, captures bigint, copies bigint)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.tt_require_admin();
  return query
  with days as (
    select generate_series(
      (now() at time zone 'Asia/Kolkata')::date - (least(greatest(p_days, 1), 180) - 1),
      (now() at time zone 'Asia/Kolkata')::date, interval '1 day')::date as d
  ), ev as (
    select (created_at at time zone 'Asia/Kolkata')::date as d, device_id, type
    from tt_events
    where created_at >= now() - make_interval(days => least(greatest(p_days, 1), 180) + 1)
  )
  select days.d,
         count(distinct ev.device_id),
         count(*) filter (where ev.type = 'capture'),
         count(*) filter (where ev.type = 'copy')
  from days left join ev on ev.d = days.d
  group by days.d order by days.d;
end $$;

create or replace function public.tt_admin_users(p_limit int default 200)
returns table (device_id text, email text, model text, android int, app_version text,
               first_seen timestamptz, last_seen timestamptz, captures bigint, copies bigint)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.tt_require_admin();
  return query
  select d.device_id, u.email::text, d.model, d.android, d.app_version, d.first_seen, d.last_seen,
         (select count(*) from tt_events e where e.device_id = d.device_id and e.type = 'capture'),
         (select count(*) from tt_events e where e.device_id = d.device_id and e.type = 'copy')
  from tt_devices d left join auth.users u on u.id = d.user_id
  order by d.last_seen desc
  limit least(greatest(p_limit, 1), 1000);
end $$;

create or replace function public.tt_admin_top_sources(p_days int default 30)
returns table (source text, copies bigint)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.tt_require_admin();
  return query
  select coalesce(nullif(e.source, ''), 'unknown'), count(*)
  from tt_events e
  where e.type = 'copy' and e.created_at >= now() - make_interval(days => least(greatest(p_days, 1), 180))
  group by 1 order by 2 desc limit 10;
end $$;

create or replace function public.tt_admin_update_settings(p_auth_mode text, p_announcement text)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.tt_require_admin();
  if p_auth_mode not in ('optional', 'required', 'off') then
    raise exception 'auth_mode must be optional, required or off';
  end if;
  update tt_settings set auth_mode = p_auth_mode, announcement = left(coalesce(p_announcement, ''), 500),
         updated_at = now() where id = 1;
end $$;

-- ---------- permissions ----------

revoke all on function public.tt_is_admin(), public.tt_require_admin(), public.tt_get_config(),
  public.tt_ping(text, text, int, text), public.tt_log_events(text, jsonb),
  public.tt_admin_overview(), public.tt_admin_daily(int), public.tt_admin_users(int),
  public.tt_admin_top_sources(int), public.tt_admin_update_settings(text, text) from public;

grant execute on function public.tt_get_config(), public.tt_ping(text, text, int, text),
  public.tt_log_events(text, jsonb) to anon, authenticated;

grant execute on function public.tt_is_admin(), public.tt_admin_overview(), public.tt_admin_daily(int),
  public.tt_admin_users(int), public.tt_admin_top_sources(int), public.tt_admin_update_settings(text, text)
  to authenticated;
