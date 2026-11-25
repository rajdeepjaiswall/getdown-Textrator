-- Guest device logs events
select tt_ping('device-abc-123', 'Xiaomi Redmi', 34, '1.0.0');
do $$ begin
  if tt_log_events('device-abc-123', '[{"type":"copy","chars":42,"source":"com.whatsapp"},{"type":"capture","chars":300},{"type":"bogus"}]') <> 2 then
    raise exception 'expected 2 valid events';
  end if;
end $$;

-- Non-admins must be rejected
do $$ begin
  perform tt_admin_overview();
  raise exception 'non-admin was allowed';
exception when insufficient_privilege then null;
end $$;

-- Admin (confirmed email on the list) can read stats and change settings
insert into auth.users values ('11111111-1111-1111-1111-111111111111', 'contact@getdownaf.info', now());
select set_config('request.uid', '11111111-1111-1111-1111-111111111111', false);
do $$ declare o jsonb; begin
  o := tt_admin_overview();
  if (o->>'total_devices')::int <> 1 or (o->>'copies_total')::int <> 1 then raise exception 'bad overview %', o; end if;
  perform tt_admin_update_settings('off', 'hello');
  if tt_get_config()->>'auth_mode' <> 'off' then raise exception 'settings not saved'; end if;
end $$;
select 'smoke tests passed' as result;
