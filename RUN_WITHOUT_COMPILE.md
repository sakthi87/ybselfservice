# Run without compiling

The API jars and the compiled UI are in the git repository. A mirror checkout does not need to download a GitHub Release.

Another machine needs Java 21+ and Python 3. It does not need Maven or Node. YugabyteDB on `localhost:5433` is required only for a real sign-in. The Demo button uses the sample dataset shipped inside the compiled UI and does not call the APIs.

## 1. Get the source

```bash
git clone https://github.com/sakthi87/ybselfservice.git
cd ybselfservice
```

## 2. Jars and compiled UI already in the checkout

| File | Location |
| --- | --- |
| `metadata-api-1.0.0.jar` | `metadata-api/target/metadata-api-1.0.0.jar` |
| `login-api-1.0.0.jar` | `login-api/target/login-api-1.0.0.jar` |
| `intake-api-1.0.0.jar` | `intake-api/target/intake-api-1.0.0.jar` |
| `execution-api-1.0.0.jar` | `execution-api/target/execution-api-1.0.0.jar` |
| `metadata-mcp-1.0.0.jar` | `metadata-mcp/target/metadata-mcp-1.0.0.jar` |
| Compiled UI | `web/dist` |

`scripts/download-prebuilt.sh` replaces those files from a GitHub Release. Skip it when release downloads are blocked.

## 3. Start

```bash
chmod +x scripts/start-prebuilt.sh scripts/stop-prebuilt.sh
./scripts/start-prebuilt.sh
```

UI: http://localhost:5173

`scripts/serve-ui.py` serves `web/dist` and forwards `/graphql`, `/login/graphql`, and `/intake/graphql` to the catalog, login, and intake jars.

## 4. Stop

```bash
./scripts/stop-prebuilt.sh
```

## Demo

Click **Demo** on the sign-in screen. Catalog, Intake, Requests, and Audit use the sample dataset in that browser, including table design and DDL upload. **Exit demo** returns to sign-in. A real user id uses the running APIs and the database.
