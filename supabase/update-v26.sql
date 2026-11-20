-- AETHER v26: Gruppen (Familie & Freunde) und „Konto löschen“.
-- In Supabase: SQL Editor → New query → einfügen → Run. (Einmalig, zusätzlich zu setup.sql)
create table if not exists public.aether_member (
  group_id text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text, avatar text, seen bigint, loc jsonb, nav jsonb,
  primary key (group_id, user_id));
alter table public.aether_member enable row level security;
create or replace function public.aether_is_member(g text) returns boolean
  language sql security definer set search_path = public stable
  as $$ select exists(select 1 from public.aether_member where group_id = g and user_id = auth.uid()) $$;
drop policy if exists "member read" on public.aether_member;
drop policy if exists "member write" on public.aether_member;
drop policy if exists "member update" on public.aether_member;
drop policy if exists "member delete" on public.aether_member;
create policy "member read" on public.aether_member for select using (public.aether_is_member(group_id));
create policy "member write" on public.aether_member for insert with check (auth.uid() = user_id);
create policy "member update" on public.aether_member for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "member delete" on public.aether_member for delete using (auth.uid() = user_id);
create or replace function public.delete_me() returns void
  language plpgsql security definer set search_path = public
  as $$ begin delete from auth.users where id = auth.uid(); end $$;
revoke all on function public.delete_me() from public, anon;
grant execute on function public.delete_me() to authenticated;
