-- Define a senha dos papeis internos do Supabase com o POSTGRES_PASSWORD do .env
\set pgpass `echo "$POSTGRES_PASSWORD"`

ALTER USER authenticator WITH PASSWORD :'pgpass';
ALTER USER supabase_auth_admin WITH PASSWORD :'pgpass';

-- O GoTrue (Supabase Auth) recria estas funcoes ao migrar o banco e so consegue se for o dono delas
DO $$
DECLARE f record;
BEGIN
  FOR f IN SELECT p.oid::regprocedure AS assinatura FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
           WHERE n.nspname = 'auth' AND pg_get_userbyid(p.proowner) <> 'supabase_auth_admin' LOOP
    EXECUTE format('ALTER FUNCTION %s OWNER TO supabase_auth_admin', f.assinatura);
  END LOOP;
END
$$;
