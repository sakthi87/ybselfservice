# API VM setup

Short-term deployment. The website stays in Kubernetes at `https://odp.company.com`. The YugabyteDB tab in that site calls this VM. The other product APIs stay in the cluster.

This VM runs four jars on localhost and nginx on port 80. nginx is the only process the cluster calls. It adds the gateway login and forwards three paths. Execution stays on localhost and is not published.

```
Kubernetes Ingress  /yugabyte/graphql
  → cluster proxy strips /yugabyte
    → http://<this-vm>/graphql
      → nginx adds the gateway login
        → 127.0.0.1:4000/graphql
```

| Path on this VM | Jar | Port |
| --- | --- | --- |
| `/graphql` | `metadata-api/target/metadata-api-1.0.0.jar` | 4000 |
| `/login/graphql` | `login-api/target/login-api-1.0.0.jar` | 4010 |
| `/intake/graphql` | `intake-api/target/intake-api-1.0.0.jar` | 4020 |
| not published | `execution-api/target/execution-api-1.0.0.jar` | 4030 |

The gateway login is `metadata-gateway` / `metadata-gateway` unless `METADATA_API_USERNAME` and `METADATA_API_PASSWORD` are changed. The sign-in user id is a different password. A person chooses that on first sign-in.

## 1. Install Java 21 and nginx

Red Hat:

```bash
sudo dnf install -y java-21-openjdk-headless nginx postgresql
java --version
psql --version
```

The version line must say 21. If it says 17:

```bash
sudo alternatives --config java
java --version
```

## 2. Copy the repository onto the VM

The jars are already in the git checkout. From the repository root, these files must exist:

```bash
ls -l \
  metadata-api/target/metadata-api-1.0.0.jar \
  login-api/target/login-api-1.0.0.jar \
  intake-api/target/intake-api-1.0.0.jar \
  execution-api/target/execution-api-1.0.0.jar
```

## 3. Point the APIs at the catalog database

YugabyteDB must accept connections from this VM. If the database is on this VM, leave the host as `localhost`. If it is elsewhere, set `YB_METADATA_HOST`.

Run bootstrap only on a fresh database. `scripts/bootstrap.sh` applies `sql/001` through `sql/014`, and `sql/002_seed.sql` truncates catalog tables.

The script uses `psql`. On this VM that is the PostgreSQL client installed above, not the Mac Homebrew path.

```bash
export PGHOST=localhost
export PGPORT=5433
export PGUSER=yugabyte
export PGDATABASE=yugabyte_metadata
export PGPASSWORD=yugabyte
export PSQL=psql
./scripts/bootstrap.sh
```

## 4. Start the four APIs on localhost

`SERVER_ADDRESS=127.0.0.1` keeps the jar ports off the network. Intake calls execution and login on localhost. Execution calls the catalog on localhost. Those calls already send the gateway login.

Save this as `/etc/odp/api.env`. Use the real database host and passwords for that environment.

```bash
sudo mkdir -p /etc/odp /var/log/odp
sudo tee /etc/odp/api.env >/dev/null <<'EOF'
SERVER_ADDRESS=127.0.0.1
METADATA_API_USERNAME=metadata-gateway
METADATA_API_PASSWORD=metadata-gateway
YB_METADATA_HOST=localhost
YB_METADATA_PORT=5433
YB_METADATA_DATABASE=yugabyte_metadata
YB_METADATA_USERNAME=metadata_reader
YB_METADATA_PASSWORD=metadata_reader
YB_ADMIN_USERNAME=yugabyte
YB_ADMIN_PASSWORD=yugabyte
EOF
sudo chmod 600 /etc/odp/api.env
```

Save this as `/etc/systemd/system/odp-api@.service`. The `%i` name is the project directory, so one unit starts each jar and restarts that jar if it exits. Replace `/opt/odp` if the repository lives somewhere else.

```ini
[Unit]
Description=Online Data Platform %i
After=network.target

[Service]
EnvironmentFile=/etc/odp/api.env
WorkingDirectory=/opt/odp
ExecStart=/usr/bin/java -jar /opt/odp/%i/target/%i-1.0.0.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now \
  odp-api@metadata-api \
  odp-api@login-api \
  odp-api@execution-api \
  odp-api@intake-api
sudo systemctl status 'odp-api@*'
```

Logs for one jar:

```bash
sudo journalctl -u odp-api@metadata-api -n 50 --no-pager
```

## 5. Test the jars on localhost

Health does not need a password. Each line should contain `"status":"UP"`.

```bash
curl -s http://127.0.0.1:4000/api/v1/health
curl -s http://127.0.0.1:4010/api/v1/health
curl -s http://127.0.0.1:4020/api/v1/health
curl -s http://127.0.0.1:4030/api/v1/health
```

Catalog GraphQL requires the gateway login. This returns the `customer` table when the sample catalog is loaded.

```bash
curl -s -u metadata-gateway:metadata-gateway \
  -H 'Content-Type: application/json' \
  http://127.0.0.1:4000/graphql \
  -d '{"query":"query($filter: TableSearch) { result: searchTables(filter: $filter) }","variables":{"filter":{"carId":"CAR-CUSTOMER-001","pageSize":"5"}}}'
```

A healthy result has `"tableName":"customer"` and no `errors` array.

Sign-in creates the account the first time that user id is used. The password must be 8 to 72 characters.

```bash
curl -s -u metadata-gateway:metadata-gateway \
  -H 'Content-Type: application/json' \
  http://127.0.0.1:4010/graphql \
  -d '{"query":"mutation($userId: String!, $password: String!) { result: signIn(userId: $userId, password: $password) { id userId subjectId } }","variables":{"userId":"alex.chen","password":"AlexPass1"}}'
```

A healthy result contains `"userId":"alex.chen"` and `"subjectId":"user:alex.chen"`.

## 6. Put nginx in front

nginx rewrites `/login/graphql` to the login jar’s `/graphql`, and `/intake/graphql` to the intake jar’s `/graphql`.

Build the header from the same username and password stored in `/etc/odp/api.env`:

```bash
printf '%s' 'metadata-gateway:metadata-gateway' | base64
```

The default value is `bWV0YWRhdGEtZ2F0ZXdheTptZXRhZGF0YS1nYXRld2F5`. Save this as `/etc/nginx/conf.d/odp-api.conf`.

```nginx
server {
    listen 80;
    server_name _;

    location = /graphql {
        proxy_pass http://127.0.0.1:4000/graphql;
        proxy_set_header Authorization "Basic bWV0YWRhdGEtZ2F0ZXdheTptZXRhZGF0YS1nYXRld2F5";
        proxy_set_header Host $host;
        proxy_set_header Content-Type $content_type;
    }

    location = /login/graphql {
        proxy_pass http://127.0.0.1:4010/graphql;
        proxy_set_header Authorization "Basic bWV0YWRhdGEtZ2F0ZXdheTptZXRhZGF0YS1nYXRld2F5";
        proxy_set_header Host $host;
        proxy_set_header Content-Type $content_type;
    }

    location = /intake/graphql {
        proxy_pass http://127.0.0.1:4020/graphql;
        proxy_set_header Authorization "Basic bWV0YWRhdGEtZ2F0ZXdheTptZXRhZGF0YS1nYXRld2F5";
        proxy_set_header Host $host;
        proxy_set_header Content-Type $content_type;
    }
}
```

On Red Hat, nginx must be allowed to connect to local ports:

```bash
sudo setsebool -P httpd_can_network_connect 1
sudo nginx -t
sudo systemctl enable --now nginx
```

Open port 80 to the Kubernetes nodes. Leave 4000, 4010, 4020, and 4030 closed.

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --reload
```

## 7. Test through nginx

These calls do not send the gateway login. nginx adds it. Run them on the VM.

```bash
curl -s -H 'Content-Type: application/json' \
  http://127.0.0.1/graphql \
  -d '{"query":"{ health { status api } }"}'
```

Expect `"status":"UP"` and `"api":"catalog"`.

```bash
curl -s -H 'Content-Type: application/json' \
  http://127.0.0.1/login/graphql \
  -d '{"query":"{ health { status api } }"}'
```

Expect `"api":"login"`.

```bash
curl -s -H 'Content-Type: application/json' \
  http://127.0.0.1/intake/graphql \
  -d '{"query":"{ health { status api } }"}'
```

Expect `"api":"intake"`.

Catalog search through nginx:

```bash
curl -s -H 'Content-Type: application/json' \
  http://127.0.0.1/graphql \
  -d '{"query":"query($filter: TableSearch) { result: searchTables(filter: $filter) }","variables":{"filter":{"carId":"CAR-CUSTOMER-001","pageSize":"5"}}}'
```

A call that omits a path nginx does not publish must fail. This one has no `location` and must not reach execution:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:4030/api/v1/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/execution/graphql
```

The first command is from the VM itself, so localhost:4030 still answers `200`. From any other machine, port 4030 must be refused. The second command should be `404` from nginx.

From a machine that is allowed through the firewall, replace `127.0.0.1` with the VM address:

```bash
curl -s -H 'Content-Type: application/json' \
  http://<vm>/graphql \
  -d '{"query":"{ health { status api } }"}'
```

## 8. What the Kubernetes website calls

The YugabyteDB tab uses a `/yugabyte` prefix so it does not collide with the other product APIs on `https://odp.company.com`. The cluster proxy strips that prefix and calls this VM:

| Path on the website | Path on this VM |
| --- | --- |
| `/yugabyte/graphql` | `/graphql` |
| `/yugabyte/login/graphql` | `/login/graphql` |
| `/yugabyte/intake/graphql` | `/intake/graphql` |

The website does not add the gateway password. When these APIs move into Kubernetes, keep those three website paths and point them at the in-cluster Services instead of this VM.
