DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metadata_reader') THEN
        CREATE ROLE metadata_reader LOGIN PASSWORD 'metadata_reader';
    END IF;
END
$$;

GRANT metadata_read TO metadata_reader;
GRANT CONNECT ON DATABASE yugabyte_metadata TO metadata_reader;
ALTER ROLE metadata_reader SET default_transaction_read_only = on;
