import { useEffect, useState } from "react";
import { gql } from "../graphql.js";
import { DesignTable } from "./DesignTable";

const TYPES = ["uuid", "text", "integer", "bigint", "boolean", "numeric", "timestamptz", "date", "jsonb", "varchar(255)"];

function newOdpReqId() {
  return `ODP-${crypto.randomUUID().replace(/-/g, "").slice(0, 8).toUpperCase()}`;
}

async function api(query, variables) {
  const data = await gql("/intake/graphql", query, variables);
  return data.result;
}

function emptyColumn() {
  return { name: "", type: "text", nullable: true, primaryKey: false };
}

export function IntakeFlow({ user }) {
  const [screen, setScreen] = useState("intent");
  const [requestType, setRequestType] = useState("NEW");
  const [change, setChange] = useState(null);
  const [pendingProjectId, setPendingProjectId] = useState("");
  const [changeDescription, setChangeDescription] = useState("");
  const [openChanges, setOpenChanges] = useState([]);
  const [savingChange, setSavingChange] = useState(false);
  const [form, setForm] = useState({
    edaiReqId: "",
    fundType: "",
    fundValue: "",
    businessLine: "",
    subDomain: "",
    domain: "",
    projectName: "",
    projectDescription: "",
    carId: "",
    carName: "",
    techOwnerEmail: "",
    businessLineOwner: "",
    developerEmail: "",
    supportAssignmentGroup: "",
    expDevDate: "",
    expITDate: "",
    uatDate: "",
    prodDate: "",
  });
  const [projects, setProjects] = useState([]);
  const [project, setProject] = useState(null);
  const [databaseName, setDatabaseName] = useState("");
  const [schemaName, setSchemaName] = useState("");
  const [activeDatabase, setActiveDatabase] = useState(null);
  const [activeSchema, setActiveSchema] = useState(null);
  const [activeTable, setActiveTable] = useState(null);
  const [uploadSql, setUploadSql] = useState("");
  const [preview, setPreview] = useState([]);
  const [executing, setExecuting] = useState(false);
  const [step, setStep] = useState("list");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    loadProjects(user.userId);
  }, [user.userId]);

  async function loadProjects(value) {
    try {
      setProjects(await api("query($userId: String!) { result: projects(userId: $userId) }", { userId: value }));
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function openProject(projectId) {
    const loaded = await api("query($projectId: ID!) { result: project(projectId: $projectId) }", { projectId });
    setProject(loaded);
    setScreen("project");
    setActiveDatabase(null);
    setActiveSchema(null);
    setActiveTable(null);
  }

  async function register(event) {
    event.preventDefault();
    setError("");
    try {
      const created = await api("mutation($input: JSON!) { result: register(input: $input) }", {
        input: { ...form, userId: user.userId, requestType: "NEW" },
      });
      setChange({ requestId: created.requestId, description: form.projectDescription || created.project_name, status: "SUBMITTED" });
      await openProject(created.project_id);
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function createDatabase(event) {
    event.preventDefault();
    setError("");
    try {
      await api("mutation($projectId: ID!, $input: JSON!) { result: createDatabase(projectId: $projectId, input: $input) }", {
        projectId: project.project_id,
        input: tracked({ databaseName }),
      });
      setDatabaseName("");
      setMessage("Database created in DEV.");
      await openProject(project.project_id);
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function createSchema(event) {
    event.preventDefault();
    setError("");
    try {
      await api("mutation($databaseId: ID!, $input: JSON!) { result: createSchema(databaseId: $databaseId, input: $input) }", {
        databaseId: activeDatabase.database_id,
        input: tracked({ schemaName }),
      });
      setSchemaName("");
      setMessage("Schema created. The public schema is not used.");
      await openProject(project.project_id);
    } catch (exception) {
      setError(exception.message);
    }
  }

  function selectDatabase(database) {
    setActiveDatabase(database);
    setActiveSchema(database.schemas?.[0] || null);
    setActiveTable(null);
    setScreen("workspace");
    setMessage("");
    setError("");
  }

  function refreshSelection(loaded) {
    const database = loaded.databases.find((item) => item.database_id === activeDatabase.database_id);
    setProject(loaded);
    setActiveDatabase(database);
    if (activeSchema) {
      setActiveSchema(database.schemas.find((item) => item.schema_id === activeSchema.schema_id) || database.schemas[0] || null);
    }
  }

  async function changeTable(mutation, body) {
    setError("");
    try {
      await api(`mutation($tableId: ID!, $input: JSON!) { result: ${mutation}(tableId: $tableId, input: $input) }`, {
        tableId: activeTable.table_id,
        input: tracked(body),
      });
      const loaded = await api("query($projectId: ID!) { result: project(projectId: $projectId) }", { projectId: project.project_id });
      refreshSelection(loaded);
      const schema = loaded.databases
        .flatMap((database) => database.schemas)
        .find((item) => item.schema_id === activeSchema.schema_id);
      setActiveTable(schema.tables.find((item) => item.table_id === activeTable.table_id));
      setMessage("Table updated in DEV.");
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function previewUpload() {
    setError("");
    try {
      const result = await api("mutation($schemaId: ID!, $input: JSON!) { result: previewDdl(schemaId: $schemaId, input: $input) }", {
        schemaId: activeSchema.schema_id,
        input: { sql: uploadSql },
      });
      setPreview(result.statements);
    } catch (exception) {
      setPreview([]);
      setError(exception.message);
    }
  }

  async function executeUpload() {
    setError("");
    setMessage("");
    setExecuting(true);
    try {
      const result = await api("mutation($schemaId: ID!, $input: JSON!) { result: executeDdl(schemaId: $schemaId, input: $input) }", {
        schemaId: activeSchema.schema_id,
        input: tracked({ sql: uploadSql }),
      });
      setPreview(result.statements || []);
      setMessage(`Executed ${result.executed} statement${result.executed === 1 ? "" : "s"} in DEV.`);
      const loaded = await api("query($projectId: ID!) { result: project(projectId: $projectId) }", { projectId: project.project_id });
      refreshSelection(loaded);
      setStep("list");
    } catch (exception) {
      setError(exception.message);
    } finally {
      setExecuting(false);
    }
  }

  async function chooseProject(projectId) {
    setError("");
    setPendingProjectId(projectId);
    try {
      const shown = await showOpenChanges(projectId);
      if (!shown) setScreen("change");
    } catch (exception) {
      setError(exception.message);
    }
  }

  function resumeChange(item) {
    setChange({
      requestId: item.request_id,
      description: item.description,
      status: "OPEN",
      workCount: Number(item.work_count || 0),
    });
    openProject(pendingProjectId).catch((exception) => setError(exception.message));
  }

  async function discardChange(requestId) {
    setError("");
    try {
      await api("mutation($projectId: ID!, $requestId: ID!) { result: discardChange(projectId: $projectId, requestId: $requestId) }", {
        projectId: pendingProjectId,
        requestId,
      });
      const open = await api("query($projectId: ID!) { result: openChanges(projectId: $projectId) }", { projectId: pendingProjectId });
      setOpenChanges(open);
      if (change?.requestId === requestId) setChange(null);
      setMessage("Empty change request deleted.");
      setScreen(open.length ? "resume" : "change");
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function finishListed(item) {
    setError("");
    try {
      await api("mutation($projectId: ID!, $requestId: ID!) { result: finishChange(projectId: $projectId, requestId: $requestId) }", {
        projectId: pendingProjectId,
        requestId: item.request_id,
      });
      if (change?.requestId === item.request_id) setChange(null);
      setMessage(`Finished ${item.request_id}.`);
      setScreen("intent");
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function finishChange() {
    if (!change || change.status !== "OPEN") {
      setChange(null);
      setScreen("intent");
      return;
    }
    setError("");
    try {
      await api("mutation($projectId: ID!, $requestId: ID!) { result: finishChange(projectId: $projectId, requestId: $requestId) }", {
        projectId: (project || {}).project_id || pendingProjectId,
        requestId: change.requestId,
      });
      setChange(null);
      setMessage(`Finished ${change.requestId}.`);
      setScreen("intent");
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function leaveChange() {
    if (change?.status === "OPEN") {
      const projectId = project?.project_id || pendingProjectId;
      setPendingProjectId(projectId);
      try {
        const open = await api("query($projectId: ID!) { result: openChanges(projectId: $projectId) }", { projectId });
        setOpenChanges(open);
        setScreen("resume");
      } catch (exception) {
        setError(exception.message);
      }
      return;
    }
    setChange(null);
    setScreen("intent");
  }

  async function showOpenChanges(projectId) {
    const open = await api("query($projectId: ID!) { result: openChanges(projectId: $projectId) }", { projectId });
    setOpenChanges(Array.isArray(open) ? open : []);
    if (Array.isArray(open) && open.length > 0) {
      setError("");
      setScreen("resume");
      return true;
    }
    return false;
  }

  async function startChange(event) {
    event.preventDefault();
    if (savingChange) return;
    setError("");
    setSavingChange(true);
    try {
      const started = await api("mutation($projectId: ID!, $input: JSON!) { result: openChange(projectId: $projectId, input: $input) }", {
        projectId: pendingProjectId,
        input: { description: changeDescription },
      });
      setChange({ ...started, status: "OPEN", workCount: 0 });
      setChangeDescription("");
      await openProject(pendingProjectId);
    } catch (exception) {
      const shown = await showOpenChanges(pendingProjectId).catch(() => false);
      if (!shown) setError(exception.message);
    } finally {
      setSavingChange(false);
    }
  }

  function tracked(body) {
    return change ? { ...body, requestId: change.requestId, requestDescription: change.description } : body;
  }

  return (
    <section className="intake">
      {error && <p className="intake-error">{error}</p>}
      {message && <p className="intake-message">{message}</p>}

      {screen === "intent" && (
        <form className="panel intake-form" onSubmit={(event) => {
          event.preventDefault();
          if (requestType === "NEW") {
            setField("edaiReqId", newOdpReqId());
            setScreen("register");
          } else {
            setScreen("existing");
          }
        }}>
          <h2>Intake</h2>
          <p className="hint">A new project receives an ODP Req ID. An existing project request continues under the project you already submitted. This intake is filed under {user.userId}.</p>
          <fieldset className="choice-set">
            <legend>What are you requesting?</legend>
            <label className="choice">
              <input type="radio" name="requestType" checked={requestType === "NEW"} onChange={() => setRequestType("NEW")} />
              <span><strong>New project request</strong><small>Start a project. The ODP Req ID is assigned for you.</small></span>
            </label>
            <label className="choice">
              <input type="radio" name="requestType" checked={requestType === "MODIFY"} onChange={() => setRequestType("MODIFY")} />
              <span><strong>Existing project request</strong><small>Add another database, schema, or table to a project you already submitted.</small></span>
            </label>
          </fieldset>
          <button className="intake-primary" type="submit">Continue</button>
        </form>
      )}

      {screen === "existing" && (
        <div className="panel">
          <h2>Project requests</h2>
          <p className="hint">Choose the project. The next step assigns a request id and description so database, table, and modification work is grouped in Audit. Promotion stays on Requests.</p>
          {projects.length === 0 && <p className="hint">You have no requests yet. Start with a new database.</p>}
          <table className="data-table">
            <thead><tr><th>ODP Req ID</th><th>Project</th><th>Domain</th><th>Business line</th><th></th></tr></thead>
            <tbody>
              {projects.map((item) => (
                <tr key={item.project_id}>
                  <td>{item.edai_req_id || "—"}</td>
                  <td>{item.project_name}</td>
                  <td>{item.domain || "—"}</td>
                  <td>{item.business_line || "—"}</td>
                  <td><button type="button" className="intake-primary" onClick={() => chooseProject(item.project_id)}>Open</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <button type="button" className="text-button" onClick={() => setScreen("intent")}>Back</button>
        </div>
      )}

      {screen === "resume" && (
        <div className="panel intake-form">
          <h2>Unfinished change</h2>
          <p className="hint">This project already has an open change, so a new one cannot start. Resume it, or close it first.</p>
          {openChanges.map((item) => {
            const hasWork = Number(item.work_count || 0) > 0;
            return (
              <article key={item.request_id} className="open-change">
                <h3>{item.request_id}</h3>
                <p>{item.description}</p>
                <p className="hint">{hasWork ? "Work is already recorded on this change. Resume it to continue, or finish it to close the audit." : "Nothing else was recorded on this change. Delete it to clear the audit, or resume it and continue."}</p>
                <div className="form-actions">
                  <button type="button" className="intake-primary" onClick={() => resumeChange(item)}>Resume</button>
                  {hasWork ? (
                    <button type="button" onClick={() => finishListed(item)}>Finish</button>
                  ) : (
                    <button type="button" onClick={() => discardChange(item.request_id)}>Delete</button>
                  )}
                </div>
              </article>
            );
          })}
          <button type="button" className="text-button" onClick={() => setScreen("existing")}>Back</button>
        </div>
      )}

      {screen === "change" && (
        <form className="panel intake-form" onSubmit={startChange}>
          <h2>Change request</h2>
          <p className="hint">This visit gets its own request id. Describe the database, table, or modification work. Audit keeps every action under that id.</p>
          <label>Description<textarea value={changeDescription} onChange={(event) => setChangeDescription(event.target.value)} required /></label>
          <div className="form-actions">
            <button type="button" className="text-button" onClick={() => setScreen("existing")}>Back</button>
            <button className="intake-primary" type="submit" disabled={savingChange}>{savingChange ? "Starting…" : "Start change request"}</button>
          </div>
        </form>
      )}

      {screen === "register" && (
        <form className="panel intake-form" onSubmit={register}>
          <h2>Project Details</h2>
          <p className="hint">The ODP Req ID is assigned for this project. The signed-in user id owns it.</p>
          <div className="intake-columns">
            <label>ODP Req ID<input value={form.edaiReqId} readOnly /></label>
            <label>Business Line<input value={form.businessLine} onChange={(event) => setField("businessLine", event.target.value)} /></label>
            <label>Fund Type
              <select value={form.fundType} onChange={(event) => setField("fundType", event.target.value)}>
                <option value="">Select</option>
                <option value="capital">Capital</option>
                <option value="operational">Operational</option>
                <option value="other">Other</option>
              </select>
            </label>
            <label>Fund Value<input value={form.fundValue} onChange={(event) => setField("fundValue", event.target.value)} /></label>
            <label>Domain<input value={form.domain} onChange={(event) => setField("domain", event.target.value)} /></label>
            <label>Sub Domain<input value={form.subDomain} onChange={(event) => setField("subDomain", event.target.value)} /></label>
            <label>Project Name *<input value={form.projectName} onChange={(event) => setField("projectName", event.target.value)} required /></label>
            <label>Project Description<textarea value={form.projectDescription} onChange={(event) => setField("projectDescription", event.target.value)} /></label>
            <label>CAR ID<input value={form.carId} onChange={(event) => setField("carId", event.target.value)} /></label>
            <label>CAR Name<input value={form.carName} onChange={(event) => setField("carName", event.target.value)} /></label>
            <label>Technical Owner<input value={form.techOwnerEmail} onChange={(event) => setField("techOwnerEmail", event.target.value)} /></label>
            <label>Business Line Owner<input value={form.businessLineOwner} onChange={(event) => setField("businessLineOwner", event.target.value)} /></label>
            <label>Developer Contact<input value={form.developerEmail} onChange={(event) => setField("developerEmail", event.target.value)} /></label>
            <label>Support Assignment Group<input value={form.supportAssignmentGroup} onChange={(event) => setField("supportAssignmentGroup", event.target.value)} /></label>
            <label>Exp. Dev Date<input type="date" value={form.expDevDate} onChange={(event) => setField("expDevDate", event.target.value)} /></label>
            <label>Exp. IT Date<input type="date" value={form.expITDate} onChange={(event) => setField("expITDate", event.target.value)} /></label>
            <label>Exp. UAT Date<input type="date" value={form.uatDate} onChange={(event) => setField("uatDate", event.target.value)} /></label>
            <label>Exp. Prod Date<input type="date" value={form.prodDate} onChange={(event) => setField("prodDate", event.target.value)} /></label>
          </div>
          <div className="form-actions">
            <button type="button" className="text-button" onClick={() => setScreen("intent")}>Back</button>
            <button className="intake-primary" type="submit">Submit intake</button>
          </div>
        </form>
      )}

      {screen === "project" && project && (
        <div className="intake-grid">
          <form className="panel intake-form" onSubmit={createDatabase}>
            <h2>{project.project_name}</h2>
            {change && <p className="hint">Request {change.requestId}. {change.description}</p>}
            <p className="hint">ODP Req ID {project.edai_req_id || "not set"}. This project can own several databases. Each one is created in DEV, then promoted on its own. The public schema is blocked.</p>
            <label>Database name<input value={databaseName} onChange={(event) => setDatabaseName(event.target.value)} required /></label>
            <button className="intake-primary" type="submit">Create database</button>
            {change?.status === "OPEN" && <button type="button" onClick={finishChange}>Finish change</button>}
            <button type="button" className="text-button" onClick={leaveChange}>Another request</button>
          </form>
          <div className="panel">
            <h2>Databases</h2>
            {(project.databases || []).length === 0 && <p className="hint">No databases yet.</p>}
            {(project.databases || []).map((database) => (
              <button key={database.database_id} type="button" className="intake-card" onClick={() => selectDatabase(database)}>
                <strong>{database.database_name}</strong>
                <span>{database.environment} · {(database.schemas || []).length} schemas</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {screen === "workspace" && activeDatabase && (
        <div className="intake-workspace">
          <div className="workspace-bar">
            <button type="button" className="intake-link" onClick={() => setScreen("project")}>Projects</button>
            <div className="workspace-context">
              <h2>{activeDatabase.database_name}</h2>
              <span className="hint">{change ? `Request ${change.requestId}` : "DEV database"}</span>
              {change?.status === "OPEN" && <button type="button" className="intake-link" onClick={finishChange}>Finish change</button>}
            </div>
            <div className="schema-switch">
              {(activeDatabase.schemas || []).map((schema) => (
                <button key={schema.schema_id} type="button" className={activeSchema?.schema_id === schema.schema_id ? "intake-card active" : "intake-card"} onClick={() => { setActiveSchema(schema); setActiveTable(null); }}>
                  {schema.schema_name} · {(schema.tables || []).length} tables
                </button>
              ))}
            </div>
            <form onSubmit={createSchema} className="intake-inline">
              <input value={schemaName} placeholder="New schema" onChange={(event) => setSchemaName(event.target.value)} required />
              <button className="intake-primary" type="submit">Create schema</button>
            </form>
          </div>

          {activeSchema && (
            <div className="panel workspace-main">
              <h2>{activeSchema.schema_name}</h2>
              <div className="tabs">
                <button type="button" className={step === "list" || step === "table" ? "tab active" : "tab"} onClick={() => { setStep("list"); setActiveTable(null); }}>Tables</button>
                <button type="button" className={step === "design" ? "tab active" : "tab"} onClick={() => setStep("design")}>Design table</button>
                <button type="button" className={step === "upload" ? "tab active" : "tab"} onClick={() => setStep("upload")}>Upload DDL</button>
              </div>

              {step === "list" && (activeSchema.tables || []).map((table) => (
                <button key={table.table_id} type="button" className="intake-card" onClick={() => { setActiveTable(table); setStep("table"); }}>
                  <strong>{table.table_name}</strong>
                  <span>{table.source} · {(table.columns || []).length} columns</span>
                </button>
              ))}
              {step === "list" && (activeSchema.tables || []).length === 0 && <p className="hint">Design a table or upload DDL.</p>}

              {step === "design" && (
                <DesignTable
                  schemaId={activeSchema.schema_id}
                  schemaName={activeSchema.schema_name}
                  changeRequest={change}
                  onCreated={async () => {
                    const loaded = await api("query($projectId: ID!) { result: project(projectId: $projectId) }", { projectId: project.project_id });
                    refreshSelection(loaded);
                  }}
                />
              )}

              {step === "upload" && (
                <div className="intake-form">
                  <h3>Upload DDL</h3>
                  <p className="hint">CREATE TABLE, CREATE INDEX, and ALTER TABLE ADD COLUMN. Objects are created in {activeSchema.schema_name}, not public.</p>
                  <textarea value={uploadSql} onChange={(event) => setUploadSql(event.target.value)} rows={8} />
                  <div className="intake-inline">
                    <button type="button" onClick={previewUpload} disabled={executing}>Preview</button>
                    <button type="button" className="intake-primary" onClick={executeUpload} disabled={executing || !uploadSql.trim()}>{executing ? "Executing..." : "Execute in DEV"}</button>
                  </div>
                  {error && <p className="intake-error">{error}</p>}
                  {preview.map((statement, index) => (
                    <article key={index} className="intake-card static">
                      <strong>{statement.kind} {statement.name}</strong>
                      <pre className="intake-ddl">{statement.sql}</pre>
                    </article>
                  ))}
                </div>
              )}

              {step === "table" && activeTable && (
                <TableEditor table={activeTable} onColumn={(body) => changeTable("addColumn", body)} onIndex={(body) => changeTable("addIndex", body)} />
              )}
            </div>
          )}
        </div>
      )}
    </section>
  );

  function setField(name, value) {
    setForm((current) => ({ ...current, [name]: value }));
  }
}

function TableEditor({ table, onColumn, onIndex }) {
  const [column, setColumn] = useState(emptyColumn());
  const [index, setIndex] = useState({ name: "", columns: "", unique: false });
  return (
    <div className="intake-form">
      <h3>{table.table_name}</h3>
      <p className="hint">{table.description || table.source}</p>
      <ul>
        {(table.columns || []).map((item) => (
          <li key={item.column_name}>{item.column_name} {item.data_type} {item.is_primary_key ? "PK" : ""}</li>
        ))}
      </ul>
      {(table.indexes || []).map((item) => (
        <p key={item.index_name}>{item.index_name} ({item.column_names}) {item.is_unique ? "unique" : ""}</p>
      ))}
      <h3>Add column</h3>
      <div className="intake-inline">
        <input placeholder="Column" value={column.name} onChange={(event) => setColumn({ ...column, name: event.target.value })} />
        <select value={column.type} onChange={(event) => setColumn({ ...column, type: event.target.value })}>
          {TYPES.map((type) => <option key={type}>{type}</option>)}
        </select>
        <button type="button" className="intake-primary" onClick={() => onColumn(column)}>Add column</button>
      </div>
      <h3>Add index</h3>
      <div className="intake-inline">
        <input placeholder="Index name" value={index.name} onChange={(event) => setIndex({ ...index, name: event.target.value })} />
        <input placeholder="col_a, col_b" value={index.columns} onChange={(event) => setIndex({ ...index, columns: event.target.value })} />
        <button type="button" className="intake-primary" onClick={() => onIndex(index)}>Add index</button>
      </div>
      <pre className="intake-ddl">{table.ddl}</pre>
    </div>
  );
}
