-- Sample topology: 2 clouds x 4 environments = 8 universes, 32 nodes.
-- The Azure PROD on-prem region id is ...016 so it does not collide
-- with the AWS PROD on-prem region id ...008.

SET search_path TO catalog, public;

TRUNCATE TABLE
    catalog.index_column_metadata,
    catalog.index_metadata,
    catalog.column_metadata,
    catalog.table_metadata,
    catalog.table_context,
    catalog.schema_metadata,
    catalog.database_metadata,
    catalog.data_owner,
    catalog.universe_node,
    catalog.universe_region,
    catalog.universe,
    catalog.environment,
    catalog.cloud;

INSERT INTO catalog.cloud
    (cloud_id, cloud_code, cloud_name, created_by, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'AWS', 'Amazon Web Services', 'bootstrap', 'bootstrap'),
    ('00000000-0000-0000-0000-000000000002', 'AZURE', 'Microsoft Azure', 'bootstrap', 'bootstrap');

INSERT INTO catalog.environment
    (environment_id, cloud_id, environment_code, environment_name, created_by, updated_by)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DEV', 'AWS Development', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'IT', 'AWS IT', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'UAT', 'AWS UAT', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'PROD', 'AWS Production', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000002', 'DEV', 'Azure Development', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000002', 'IT', 'Azure IT', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000002', 'UAT', 'Azure UAT', 'bootstrap', 'bootstrap'),
    ('10000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000002', 'PROD', 'Azure Production', 'bootstrap', 'bootstrap');

INSERT INTO catalog.universe (
    universe_id, environment_id, universe_code, universe_name, status, topology,
    yugabyte_version, replication_factor, expected_node_count, region_count,
    master_count, tserver_count, fault_tolerance_level, api_endpoint, configuration,
    created_by, updated_by
)
VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'AWS-DEV-YB01', 'AWS DEV Universe', 'ACTIVE', 'SINGLE_REGION',
     '2025.2', 3, 3, 1, 3, 3, 'ZONE',
     'http://aws-dev-yb01.internal:9000',
     '{"cloud":"AWS","environment":"DEV","ybSoftwareVersion":"2025.2","ysql":{"enabled":true,"port":5433},"ycql":{"enabled":true,"port":9042},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
     'AWS-IT-YB01', 'AWS IT Universe', 'ACTIVE', 'SINGLE_REGION',
     '2025.2', 3, 3, 1, 3, 3, 'ZONE',
     'http://aws-it-yb01.internal:9000',
     '{"cloud":"AWS","environment":"IT","ybSoftwareVersion":"2025.2","ysql":{"enabled":true,"port":5433},"ycql":{"enabled":true,"port":9042},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003',
     'AWS-UAT-YB01', 'AWS UAT Universe', 'ACTIVE', 'STRETCH_CLUSTER',
     '2025.2', 5, 5, 3, 5, 5, 'REGION',
     'http://aws-uat-yb01.internal:9000',
     '{"cloud":"AWS","environment":"UAT","stretchCluster":true,"placement":{"faultTolerance":"REGION","leaderPreference":["aws.us-east-1","aws.us-east-2"]},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000004',
     'AWS-PROD-YB01', 'AWS Production Universe', 'ACTIVE', 'STRETCH_CLUSTER',
     '2025.2', 5, 5, 3, 5, 5, 'REGION',
     'http://aws-prod-yb01.internal:9000',
     '{"cloud":"AWS","environment":"PROD","stretchCluster":true,"placement":{"faultTolerance":"REGION","leaderPreference":["aws.us-east-1","aws.us-east-2"]},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000005',
     'AZURE-DEV-YB01', 'Azure DEV Universe', 'ACTIVE', 'SINGLE_REGION',
     '2025.2', 3, 3, 1, 3, 3, 'ZONE',
     'http://azure-dev-yb01.internal:9000',
     '{"cloud":"AZURE","environment":"DEV","ybSoftwareVersion":"2025.2","ysql":{"enabled":true,"port":5433},"ycql":{"enabled":true,"port":9042},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000006',
     'AZURE-IT-YB01', 'Azure IT Universe', 'ACTIVE', 'SINGLE_REGION',
     '2025.2', 3, 3, 1, 3, 3, 'ZONE',
     'http://azure-it-yb01.internal:9000',
     '{"cloud":"AZURE","environment":"IT","ybSoftwareVersion":"2025.2","ysql":{"enabled":true,"port":5433},"ycql":{"enabled":true,"port":9042},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000007',
     'AZURE-UAT-YB01', 'Azure UAT Universe', 'ACTIVE', 'STRETCH_CLUSTER',
     '2025.2', 5, 5, 3, 5, 5, 'REGION',
     'http://azure-uat-yb01.internal:9000',
     '{"cloud":"AZURE","environment":"UAT","stretchCluster":true,"placement":{"faultTolerance":"REGION","leaderPreference":["azure.centralus","azure.eastus"]},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap'),
    ('20000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000008',
     'AZURE-PROD-YB01', 'Azure Production Universe', 'ACTIVE', 'STRETCH_CLUSTER',
     '2025.2', 5, 5, 3, 5, 5, 'REGION',
     'http://azure-prod-yb01.internal:9000',
     '{"cloud":"AZURE","environment":"PROD","stretchCluster":true,"placement":{"faultTolerance":"REGION","leaderPreference":["azure.centralus","azure.eastus"]},"security":{"tlsEnabled":true,"encryptionAtRest":true}}',
     'bootstrap', 'bootstrap');

INSERT INTO catalog.universe_region (
    universe_region_id, universe_id, location_type, cloud_provider,
    region_code, region_name, node_count, leader_preference, is_primary_region,
    created_by, updated_by
)
VALUES
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'CLOUD', 'AWS', 'us-east-1', 'AWS US East 1', 3, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'CLOUD', 'AWS', 'us-east-1', 'AWS US East 1', 3, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', 'CLOUD', 'AWS', 'us-east-1', 'AWS US East 1', 2, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000003', 'CLOUD', 'AWS', 'us-east-2', 'AWS US East 2', 2, 2, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000003', 'ONPREM', NULL, 'ONPREM-DC01', 'On-Prem DC 01', 1, NULL, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000004', 'CLOUD', 'AWS', 'us-east-1', 'AWS US East 1', 2, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000004', 'CLOUD', 'AWS', 'us-east-2', 'AWS US East 2', 2, 2, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000004', 'ONPREM', NULL, 'ONPREM-DC01', 'On-Prem DC 01', 1, NULL, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000005', 'CLOUD', 'AZURE', 'centralus', 'Azure Central US', 3, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000006', 'CLOUD', 'AZURE', 'centralus', 'Azure Central US', 3, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000007', 'CLOUD', 'AZURE', 'centralus', 'Azure Central US', 2, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000007', 'CLOUD', 'AZURE', 'eastus', 'Azure East US', 2, 2, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000007', 'ONPREM', NULL, 'ONPREM-DC01', 'On-Prem DC 01', 1, NULL, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000014', '20000000-0000-0000-0000-000000000008', 'CLOUD', 'AZURE', 'centralus', 'Azure Central US', 2, 1, TRUE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000015', '20000000-0000-0000-0000-000000000008', 'CLOUD', 'AZURE', 'eastus', 'Azure East US', 2, 2, FALSE, 'bootstrap', 'bootstrap'),
    ('30000000-0000-0000-0000-000000000016', '20000000-0000-0000-0000-000000000008', 'ONPREM', NULL, 'ONPREM-DC01', 'On-Prem DC 01', 1, NULL, FALSE, 'bootstrap', 'bootstrap');

WITH expanded AS (
    SELECT
        r.universe_id,
        r.universe_region_id,
        r.region_code,
        r.location_type,
        u.universe_code,
        u.expected_node_count,
        c.cloud_code,
        e.environment_code,
        s AS series,
        row_number() OVER (
            PARTITION BY r.universe_id
            ORDER BY r.leader_preference NULLS LAST, r.region_code, s
        ) AS node_seq
    FROM catalog.universe_region r
    JOIN catalog.universe u
      ON u.universe_id = r.universe_id
    JOIN catalog.environment e
      ON e.environment_id = u.environment_id
    JOIN catalog.cloud c
      ON c.cloud_id = e.cloud_id
    CROSS JOIN LATERAL generate_series(1, r.node_count) AS s
)
INSERT INTO catalog.universe_node (
    universe_id,
    universe_region_id,
    node_name,
    host_name,
    private_ip,
    node_role,
    status,
    cloud_instance_type,
    cpu_cores,
    memory_gib,
    storage_total_gib,
    storage_used_gib,
    storage_available_gib,
    cpu_utilization_percent,
    memory_utilization_percent,
    storage_utilization_percent,
    os_version,
    configuration,
    created_by,
    updated_by
)
SELECT
    universe_id,
    universe_region_id,
    'yb-' || lower(universe_code) || '-' || lpad(node_seq::text, 2, '0'),
    'yb-' || lower(universe_code) || '-' || lpad(node_seq::text, 2, '0') || '.internal',
    (
        ('10.' ||
            CASE cloud_code WHEN 'AWS' THEN '1' ELSE '2' END || '.' ||
            CASE environment_code
                WHEN 'DEV' THEN '10'
                WHEN 'IT' THEN '20'
                WHEN 'UAT' THEN '30'
                ELSE '40'
            END || '.0')::inet + node_seq
    ),
    'MASTER_TSERVER',
    'ACTIVE',
    CASE WHEN expected_node_count = 3 THEN 'standard-3node' ELSE 'standard-5node' END,
    CASE WHEN expected_node_count = 3 THEN 8 ELSE 16 END,
    CASE WHEN expected_node_count = 3 THEN 32 ELSE 64 END,
    CASE WHEN expected_node_count = 3 THEN 500 ELSE 1000 END,
    round((CASE WHEN expected_node_count = 3 THEN 500 ELSE 1000 END) * (0.22 + series * 0.06), 2),
    round((CASE WHEN expected_node_count = 3 THEN 500 ELSE 1000 END) * (1 - (0.22 + series * 0.06)), 2),
    round(18 + series * 7, 2),
    round(30 + series * 5, 2),
    round((0.22 + series * 0.06) * 100, 2),
    'RHEL 9',
    jsonb_build_object(
        'locationType', location_type,
        'region', region_code,
        'bootstrap', true
    ),
    'bootstrap',
    'bootstrap'
FROM expanded;

INSERT INTO catalog.data_owner (
    data_owner_id, owner_name,
    primary_contact_name, primary_contact_email,
    secondary_contact_name, secondary_contact_email,
    created_by, updated_by
)
VALUES
    ('DOWNER-001', 'Customer Data Platform',
     'John Smith', 'john.smith@example.com',
     'Jane Doe', 'jane.doe@example.com',
     'bootstrap', 'bootstrap'),
    ('DOWNER-002', 'Finance Data Platform',
     'Alice Brown', 'alice.brown@example.com',
     'Bob Green', 'bob.green@example.com',
     'bootstrap', 'bootstrap');

INSERT INTO catalog.database_metadata (
    database_id, universe_id, database_name, database_owner, definition_sql, created_by, updated_by
)
VALUES
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004',
     'customerdb', 'Customer Data Platform', 'CREATE DATABASE customerdb;', 'bootstrap', 'bootstrap'),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000004',
     'finance', 'Finance Data Platform', 'CREATE DATABASE finance;', 'bootstrap', 'bootstrap');

INSERT INTO catalog.schema_metadata (
    schema_id, database_id, schema_name, schema_owner, definition_sql, created_by, updated_by
)
VALUES
    ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
     'customer', 'Customer Data Platform', 'CREATE SCHEMA customer;', 'bootstrap', 'bootstrap'),
    ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002',
     'finance', 'Finance Data Platform', 'CREATE SCHEMA finance;', 'bootstrap', 'bootstrap');

INSERT INTO catalog.table_context (
    table_context_id, car_id, business_line, product_line, system_of_origin_code,
    estimated_storage_volume, actual_storage_volume, estimated_tps, actual_tps,
    created_by, updated_by
)
VALUES
    ('70000000-0000-0000-0000-000000000001', 'CAR-CUSTOMER-001', 'CUSTOMER',
     'Customer Data Platform', 'CDP', 80000000, 73400320, 50, 20,
     'bootstrap', 'bootstrap'),
    ('70000000-0000-0000-0000-000000000002', 'CAR-FINANCE-001', 'FINANCE',
     'Finance Data Platform', 'FDP', 50000000, 41943040, 30, 12,
     'bootstrap', 'bootstrap');

INSERT INTO catalog.table_metadata (
    table_id, schema_id, data_owner_id, table_context_id, table_name, table_type, status,
    row_count_estimate, size_bytes, definition_sql, properties, created_by, updated_by
)
VALUES
    ('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
     'DOWNER-001', '70000000-0000-0000-0000-000000000001', 'customer', 'BASE_TABLE', 'ACTIVE', 128440, 73400320,
     'CREATE TABLE customer.customer (
        customer_id UUID PRIMARY KEY,
        customer_name TEXT NOT NULL,
        email TEXT,
        created_at TIMESTAMPTZ NOT NULL
    );',
     '{"classification":"CONFIDENTIAL","business_domain":"CUSTOMER"}',
     'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000002',
     'DOWNER-002', '70000000-0000-0000-0000-000000000002', 'invoice', 'BASE_TABLE', 'ACTIVE', 90211, 41943040,
     'CREATE TABLE finance.invoice (
        invoice_id UUID PRIMARY KEY,
        customer_id UUID NOT NULL,
        invoice_amount NUMERIC(18,2) NOT NULL,
        invoice_date DATE NOT NULL
    );',
     '{"classification":"RESTRICTED","business_domain":"FINANCE"}',
     'bootstrap', 'bootstrap');

INSERT INTO catalog.column_metadata (
    table_id, column_name, ordinal_position, data_type, is_nullable, comment, created_by, updated_by
)
VALUES
    ('60000000-0000-0000-0000-000000000001', 'customer_id', 1, 'uuid', FALSE, 'Customer unique identifier', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000001', 'customer_name', 2, 'text', FALSE, 'Customer legal name', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000001', 'email', 3, 'text', TRUE, 'Customer email', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000001', 'created_at', 4, 'timestamptz', FALSE, 'Creation timestamp', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000002', 'invoice_id', 1, 'uuid', FALSE, 'Invoice unique identifier', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000002', 'customer_id', 2, 'uuid', FALSE, 'Owning customer', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000002', 'invoice_amount', 3, 'numeric', FALSE, 'Invoice amount', 'bootstrap', 'bootstrap'),
    ('60000000-0000-0000-0000-000000000002', 'invoice_date', 4, 'date', FALSE, 'Invoice date', 'bootstrap', 'bootstrap');

UPDATE catalog.column_metadata
   SET numeric_precision = 18,
       numeric_scale = 2
 WHERE table_id = '60000000-0000-0000-0000-000000000002'
   AND column_name = 'invoice_amount';

INSERT INTO catalog.index_metadata (
    index_id, table_id, index_name, index_kind, index_method,
    is_unique, is_primary, index_definition, created_by, updated_by
)
VALUES
    ('70000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001',
     'customer_pkey', 'PRIMARY_KEY', 'LSM', TRUE, TRUE,
     'PRIMARY KEY (customer_id)', 'bootstrap', 'bootstrap'),
    ('70000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001',
     'customer_email_idx', 'UNIQUE', 'LSM', TRUE, FALSE,
     'CREATE UNIQUE INDEX customer_email_idx ON customer.customer(email)',
     'bootstrap', 'bootstrap'),
    ('70000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000002',
     'invoice_pkey', 'PRIMARY_KEY', 'LSM', TRUE, TRUE,
     'PRIMARY KEY (invoice_id)', 'bootstrap', 'bootstrap');

INSERT INTO catalog.index_column_metadata (
    index_id, ordinal_position, column_name, sort_direction, nulls_position,
    is_included_column, created_by, updated_by
)
VALUES
    ('70000000-0000-0000-0000-000000000001', 1, 'customer_id', 'ASC', 'LAST', FALSE, 'bootstrap', 'bootstrap'),
    ('70000000-0000-0000-0000-000000000002', 1, 'email', 'ASC', 'LAST', FALSE, 'bootstrap', 'bootstrap'),
    ('70000000-0000-0000-0000-000000000003', 1, 'invoice_id', 'ASC', 'LAST', FALSE, 'bootstrap', 'bootstrap');
