-- Tokens do Notion de cada usuario.
-- O id é o ID do usuário no Supabase Auth (auth.users). Os tokens já chegam aqui criptografados (Fernet) pela API.
create table if not exists public.usuarios (
    id              text primary key,
    token_acesso    text,
    token_renovacao text,
    bot_id          text,
    espaco_id       text,
    espaco_nome     text,
    espaco_icone    text,
    status          text not null default 'conectado' constraint usuarios_status_check check (status in ('conectado', 'reconectar', 'desconectado')),
    -- o cadastro so conta quando o usuario toca em "Finalizar"; ate la a conta e provisoria
    cadastro_concluido boolean not null default false,
    atualizado_em   timestamptz not null default now()
);

-- bancos criados antes destas mudancas (desconectar o Notion limpa os tokens, mas a linha fica com a marca do cadastro)
alter table public.usuarios add column if not exists cadastro_concluido boolean not null default false;
alter table public.usuarios alter column token_acesso drop not null;
alter table public.usuarios drop constraint if exists usuarios_status_check;
alter table public.usuarios add constraint usuarios_status_check check (status in ('conectado', 'reconectar', 'desconectado'));

create or replace function public.usuarios_atualizado_em() returns trigger
language plpgsql as $$
begin
    new.atualizado_em = now();
    return new;
end;
$$;

drop trigger if exists usuarios_atualizado_em on public.usuarios;
create trigger usuarios_atualizado_em before update on public.usuarios
    for each row execute function public.usuarios_atualizado_em();

-- Ninguem alem da API (service_role) enxerga a tabela: RLS ligada, sem nenhuma politica
alter table public.usuarios enable row level security;
revoke all on public.usuarios from anon, authenticated;
grant select, insert, update, delete on public.usuarios to service_role;

-- Contas de login (auth.users) que nunca terminaram o cadastro e ja passaram de `minutos`.
-- A API apaga essas contas; so ela (service_role) pode chamar.
create or replace function public.cadastros_incompletos(minutos int default 30)
returns setof text
language sql
security definer
set search_path = public, auth
as $$
    select u.id::text
    from auth.users u
    left join public.usuarios x on x.id = u.id::text
    where coalesce(x.cadastro_concluido, false) = false
      and u.created_at < now() - make_interval(mins => minutos)
$$;

revoke all on function public.cadastros_incompletos(int) from public, anon, authenticated;
grant execute on function public.cadastros_incompletos(int) to service_role;

-- avisa o PostgREST para reler o esquema (enxergar colunas e funcoes novas sem reiniciar)
notify pgrst, 'reload schema';
