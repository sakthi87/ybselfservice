import { useEffect, useMemo, useState } from "react";
import { getDatabases, getOptions, getSchemaTables, getSchemas, getTable, getTableDefinition, searchTables } from "../api.js";

const EMPTY_FILTERS = {
  cloud: "",
  environment: "",
  universe: "",
  databaseId: "",
  schemaId: "",
  tableName: "",
  owner: "",
  q: "",
};

function universesFor(options, cloud, environment) {
  if (!options) return [];
  return options.universes.filter((universe) => {
    if (cloud && universe.cloud !== cloud) return false;
    if (environment && universe.environment !== environment) return false;
    return true;
  });
}

export function TableCatalog({ user }) {
  const [options, setOptions] = useState(null);
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [databases, setDatabases] = useState([]);
  const [schemas, setSchemas] = useState([]);
  const [schemaTables, setSchemaTables] = useState([]);
  const [tables, setTables] = useState([]);
  const [total, setTotal] = useState(0);
  const [view, setView] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [scope, setScope] = useState("explore");

  const universes = useMemo(
    () => universesFor(options, filters.cloud, filters.environment),
    [options, filters.cloud, filters.environment]
  );

  useEffect(() => {
    getOptions()
      .then(setOptions)
      .catch((err) => setError(err.message));
  }, [user.userId]);

  useEffect(() => {
    if (!filters.universe) {
      setDatabases([]);
      return undefined;
    }
    let cancelled = false;
    getDatabases(filters.universe)
      .then((rows) => {
        if (!cancelled) setDatabases(rows);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [filters.universe]);

  useEffect(() => {
    if (!filters.databaseId) {
      setSchemas([]);
      return undefined;
    }
    let cancelled = false;
    getSchemas(filters.databaseId)
      .then((rows) => {
        if (!cancelled) setSchemas(rows);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [filters.databaseId]);

  useEffect(() => {
    if (!filters.schemaId) {
      setSchemaTables([]);
      return undefined;
    }
    let cancelled = false;
    getSchemaTables(filters.schemaId)
      .then((rows) => {
        if (!cancelled) setSchemaTables(rows);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [filters.schemaId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const database = databases.find((item) => item.databaseId === applied.databaseId)?.databaseName;
    const schema = schemas.find((item) => item.schemaId === applied.schemaId)?.schemaName;
    searchTables({
      cloud: applied.cloud,
      environment: applied.environment,
      universe: applied.universe,
      database: database || "",
      schema: schema || "",
      table: applied.tableName,
      owner: scope === "owned" ? "" : applied.owner,
      ownerUserId: scope === "owned" ? user.userId : "",
      q: applied.q,
    })
      .then((body) => {
        if (cancelled) return;
        setTables(body.items);
        setTotal(body.page.total);
        setError("");
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applied, databases, schemas, scope, user.userId]);

  useEffect(() => {
    if (!view) return undefined;
    function onKey(event) {
      if (event.key === "Escape") setView(null);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [view]);

  function openView(table) {
    setView({ summary: table, loading: true, error: "" });
    Promise.all([getTable(table.tableId), getTableDefinition(table.tableId)])
      .then(([detail, definition]) => {
        setView({ summary: table, detail, definition, loading: false, error: "" });
      })
      .catch((err) => {
        setView({ summary: table, loading: false, error: err.message });
      });
  }

  function commit(next) {
    setFilters(next);
    setApplied(next);
  }

  function clearBelowUniverse() {
    setDatabases([]);
    setSchemas([]);
    setSchemaTables([]);
  }

  function onCloud(value) {
    const stillValid =
      !filters.universe ||
      universesFor(options, value, filters.environment).some((universe) => universe.code === filters.universe);
    if (!stillValid) clearBelowUniverse();
    commit({
      ...filters,
      cloud: value,
      ...(stillValid
        ? {}
        : { universe: "", databaseId: "", schemaId: "", tableName: "" }),
    });
  }

  function onEnvironment(value) {
    const stillValid =
      !filters.universe ||
      universesFor(options, filters.cloud, value).some((universe) => universe.code === filters.universe);
    if (!stillValid) clearBelowUniverse();
    commit({
      ...filters,
      environment: value,
      ...(stillValid
        ? {}
        : { universe: "", databaseId: "", schemaId: "", tableName: "" }),
    });
  }

  function onUniverse(value) {
    clearBelowUniverse();
    commit({
      ...filters,
      universe: value,
      databaseId: "",
      schemaId: "",
      tableName: "",
    });
  }

  function onDatabase(value) {
    setSchemas([]);
    setSchemaTables([]);
    commit({
      ...filters,
      databaseId: value,
      schemaId: "",
      tableName: "",
    });
  }

  function onSchema(value) {
    setSchemaTables([]);
    commit({
      ...filters,
      schemaId: value,
      tableName: "",
    });
  }

  function applyFilters(event) {
    event.preventDefault();
    setApplied(filters);
  }

  return (
    <section className="workspace">
      <div className="scope-bar">
        <div className="scope-switch" role="tablist" aria-label="Catalog scope">
          <button type="button" className={scope === "explore" ? "scope active" : "scope"} onClick={() => setScope("explore")}>Explore</button>
          <button type="button" className={scope === "owned" ? "scope active" : "scope"} onClick={() => setScope("owned")}>Owned by me</button>
        </div>
        <p>{scope === "explore" ? "Every database in the platform, for discovery." : `Tables owned by ${user.userId}.`}</p>
      </div>
      <form className="filters" onSubmit={applyFilters}>
        <label>
          Cloud
          <select value={filters.cloud} onChange={(event) => onCloud(event.target.value)}>
            <option value="">All</option>
            {options?.clouds.map((cloud) => (
              <option key={cloud.code} value={cloud.code}>
                {cloud.code}
              </option>
            ))}
          </select>
        </label>
        <label>
          Environment
          <select
            value={filters.environment}
            onChange={(event) => onEnvironment(event.target.value)}
          >
            <option value="">All</option>
            {options?.environments.map((environment) => (
              <option key={environment} value={environment}>
                {environment}
              </option>
            ))}
          </select>
        </label>
        <label>
          Universe
          <select
            value={filters.universe}
            onChange={(event) => onUniverse(event.target.value)}
          >
            <option value="">All</option>
            {universes.map((universe) => (
              <option key={universe.code} value={universe.code}>
                {universe.code}
              </option>
            ))}
          </select>
        </label>
        <label>
          Database
          <select
            value={filters.databaseId}
            disabled={!filters.universe}
            onChange={(event) => onDatabase(event.target.value)}
          >
            <option value="">{filters.universe ? "All" : "Select a universe"}</option>
            {databases.map((database) => (
              <option key={database.databaseId} value={database.databaseId}>
                {database.databaseName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Schema
          <select
            value={filters.schemaId}
            disabled={!filters.databaseId}
            onChange={(event) => onSchema(event.target.value)}
          >
            <option value="">{filters.databaseId ? "All" : "Select a database"}</option>
            {schemas.map((schema) => (
              <option key={schema.schemaId} value={schema.schemaId}>
                {schema.schemaName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Table
          <select
            value={filters.tableName}
            disabled={!filters.schemaId}
            onChange={(event) => commit({ ...filters, tableName: event.target.value })}
          >
            <option value="">{filters.schemaId ? "All" : "Select a schema"}</option>
            {schemaTables.map((table) => (
              <option key={table.tableId} value={table.tableName}>
                {table.tableName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Owner
          <select
            value={filters.owner}
            onChange={(event) => commit({ ...filters, owner: event.target.value })}
          >
            <option value="">All</option>
            {options?.owners.map((owner) => (
              <option key={owner.id} value={owner.id}>
                {owner.id}
              </option>
            ))}
          </select>
        </label>
        <label className="search-field">
          Search
          <input
            type="search"
            value={filters.q}
            onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))}
            placeholder="Search table, owner, database, schema"
          />
        </label>
        <button type="submit">Search</button>
      </form>

      {error ? <p className="banner error">{error}</p> : null}

      <div className="panel catalog-list">
        <div className="panel-head">
          <h2>Tables</h2>
          <span>{loading ? "Loading" : `${total} found`}</span>
        </div>
        {tables.length === 0 && !loading ? (
          <p className="empty">No tables match these filters. Sample tables are loaded on AWS-PROD-YB01.</p>
        ) : (
          <div className="table-scroll">
            <table className="data-table catalog-table">
              <thead>
                <tr>
                  <th>Table</th>
                  <th>Cloud</th>
                  <th>Environment</th>
                  <th>Universe</th>
                  <th>Database</th>
                  <th>Schema</th>
                  <th>Owner</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {tables.map((table) => (
                  <tr key={table.tableId}>
                    <td>{table.tableName}</td>
                    <td>{table.cloud}</td>
                    <td>{table.environment}</td>
                    <td>{table.universeCode}</td>
                    <td>{table.databaseName}</td>
                    <td>{table.schemaName}</td>
                    <td>{table.ownerName}</td>
                    <td><span className={`pill ${(table.status || "").toLowerCase()}`}>{table.status}</span></td>
                    <td><button type="button" className="text-button" onClick={() => openView(table)}>View</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {view ? <TableDialog view={view} onClose={() => setView(null)} /> : null}
      <p className="hint">
        {universes.length} universes visible for the current cloud and environment filters.
      </p>
    </section>
  );
}

function columnLabel(column) {
  if (typeof column === "string") return column;
  return column?.name || column?.expression || "";
}

function TableDialog({ view, onClose }) {
  const { summary, detail, definition, loading, error } = view;
  const ysql = definition?.target?.ysql || {};
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="table-dialog-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="panel-head">
          <div>
            <p className="crumb">
              {summary.cloud} / {summary.environment} / {summary.universeCode} / {summary.databaseName} / {summary.schemaName}
            </p>
            <h2 id="table-dialog-title">{summary.tableName}</h2>
          </div>
          <button type="button" className="text-button" onClick={onClose}>Close</button>
        </div>
        {loading ? <p className="empty">Loading definition…</p> : null}
        {error ? <p className="banner error">{error}</p> : null}
        {detail && definition ? (
          <>
            <dl className="facts">
              <div>
                <dt>Qualified name</dt>
                <dd>{definition.target?.qualifiedName || summary.qualifiedName}</dd>
              </div>
              <div>
                <dt>Owner</dt>
                <dd>
                  {detail.owner.name}
                  <span>{detail.owner.id}</span>
                </dd>
              </div>
              <div>
                <dt>Primary contact</dt>
                <dd>{detail.owner.primaryContact || "—"}</dd>
              </div>
              <div>
                <dt>Secondary contact</dt>
                <dd>{detail.owner.secondaryContact || "—"}</dd>
              </div>
              <div>
                <dt>Type</dt>
                <dd>
                  {detail.tableInfo.type}
                  <span>{detail.tableInfo.partitioned ? "Partitioned" : "Not partitioned"}</span>
                </dd>
              </div>
              <div>
                <dt>Classification</dt>
                <dd>
                  {detail.metadata.classification || "—"}
                  <span>{detail.metadata.businessDomain || ""}</span>
                </dd>
              </div>
              <div>
                <dt>YSQL</dt>
                <dd>
                  {ysql.enabled ? "Enabled" : "Disabled"}
                  <span>{ysql.port ? `port ${ysql.port}` : definition.target?.apiEndpoint || ""}</span>
                </dd>
              </div>
              <div>
                <dt>Partition</dt>
                <dd>
                  {definition.partitioning?.strategy || "—"}
                  <span>{definition.partitioning?.definition || ""}</span>
                </dd>
              </div>
            </dl>

            <h3>Columns</h3>
            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Nullable</th>
                    <th>Key</th>
                    <th>Comment</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.columns.map((column, index) => (
                    <tr key={column.name}>
                      <td>{index + 1}</td>
                      <td>{column.name}</td>
                      <td>{column.type}</td>
                      <td>{column.nullable ? "yes" : "no"}</td>
                      <td>{column.primaryKey ? "PK" : ""}</td>
                      <td>{column.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <h3>Indexes</h3>
            <ul className="indexes">
              {detail.indexes.map((index) => (
                <li key={index.name}>
                  <strong>{index.name}</strong>
                  <span>
                    {index.type}
                    {index.unique ? " · unique" : ""}
                  </span>
                  <code>{(index.columns || []).map(columnLabel).join(", ")}</code>
                </li>
              ))}
            </ul>

            <h3>DDL</h3>
            <DdlBlock ddl={definition.ddl} />
          </>
        ) : null}
      </div>
    </div>
  );
}

function DdlBlock({ ddl }) {
  const [copied, setCopied] = useState(false);

  async function copyDdl() {
    if (!ddl) return;
    try {
      await navigator.clipboard.writeText(ddl);
    } catch {
      const area = document.createElement("textarea");
      area.value = ddl;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.left = "-9999px";
      document.body.appendChild(area);
      area.select();
      const copiedText = document.execCommand("copy");
      area.remove();
      if (!copiedText) return;
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="ddl-wrap">
      <button
        type="button"
        className="copy-button"
        aria-label={copied ? "Copied" : "Copy DDL"}
        title={copied ? "Copied" : "Copy DDL"}
        onClick={copyDdl}
        disabled={!ddl}
      >
        {copied ? <CheckIcon /> : <CopyIcon />}
      </button>
      <pre className="ddl-block">{ddl || "No DDL stored for this table."}</pre>
    </div>
  );
}

function CopyIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="9" width="13" height="13" rx="2" />
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}
