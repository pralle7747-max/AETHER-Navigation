-- AETHER: Tabellen für Konto-Sync. In Supabase: SQL Editor → New query → einfügen → Run.
create table if not exists public.aether_kv (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  k text not null, v jsonb, ts bigint not null default 0,
  primary key (user_id, k));
create table if not exists public.aether_dev (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  id text not null, name text, pc boolean default false, seen bigint, loc jsonb,
  primary key (user_id, id));
create table if not exists public.aether_msg (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  from_id text, from_name text, to_id text, title text, code text, t bigint);
alter table public.aether_kv enable row level security;
alter table public.aether_dev enable row level security;
alter table public.aether_msg enable row level security;
create policy "own kv" on public.aether_kv for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own dev" on public.aether_dev for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own msg" on public.aether_msg for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
