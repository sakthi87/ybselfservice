import { useEffect, useState } from "react";
import { gql } from "../graphql.js";

const ACTIONS = ["", "CHANGE_STARTED", "CHANGE_COMPLETED", "INTAKE_SUBMITTED", "DATABASE_CREATED", "SCHEMA_CREATED", "TABLE_DESIGNED", "COLUMN_ADDED", "INDEX_ADDED", "DDL_EXECUTED", "PROMOTED"];

export function AuditLog({ user }) {
  const [rows, setRows] = useState([]);
  const [error, setError] = useState("");
  const [requestId, setRequestId] = useState("");
  const [action, setAction] = useState("");
  const [q, setQ] = useState("");

  useEffect(() => {
    gql("/intake/graphql", "query($userId: String!, $requestId: String, $action: String, $q: String) { result: activity(userId: $userId, requestId: $requestId, action: $action, q: $q) }", {
      userId: user.userId,
      requestId: requestId || null,
      action: action || null,
      q: q || null,
    })
      .then((data) => {
        setRows(data.result);
        setError("");
      })
      .catch((exception) => setError(exception.message));
  }, [user.userId, requestId, action, q]);

  return (
    <section className="audit">
      <header className="section-heading">
        <div>
          <h2>Audit</h2>
          <p>Activity for {user.userId}. Each change request shares one request id.</p>
        </div>
      </header>
      <form className="audit-filters" onSubmit={(event) => event.preventDefault()}>
        <label>Request id<input value={requestId} onChange={(event) => setRequestId(event.target.value)} placeholder="REQ-" /></label>
        <label>Action
          <select value={action} onChange={(event) => setAction(event.target.value)}>
            {ACTIONS.map((item) => <option key={item || "all"} value={item}>{item ? label(item) : "All actions"}</option>)}
          </select>
        </label>
        <label>Description<input value={q} onChange={(event) => setQ(event.target.value)} placeholder="Search description or object" /></label>
      </form>
      {error && <p className="form-error">{error}</p>}
      <table className="data-table">
        <thead>
          <tr><th>When</th><th>Request</th><th>Description</th><th>Action</th><th>Object</th></tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.activity_id}>
              <td>{formatTime(row.created_at)}</td>
              <td>{row.request_id || "—"}</td>
              <td>{row.description || row.detail || "—"}</td>
              <td>{label(row.action)}</td>
              <td>{[row.object_type, row.object_name].filter(Boolean).join(" · ")}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && !error && <p className="empty-copy">No activity matches these filters.</p>}
    </section>
  );
}

function label(action) {
  return {
    SIGN_IN: "Signed in",
    INTAKE_SUBMITTED: "Project submitted",
    CHANGE_STARTED: "Change request started",
    CHANGE_COMPLETED: "Change request finished",
    DATABASE_CREATED: "Database created",
    SCHEMA_CREATED: "Schema created",
    TABLE_DESIGNED: "Table designed",
    COLUMN_ADDED: "Column added",
    INDEX_ADDED: "Index added",
    DDL_EXECUTED: "DDL executed",
    PROMOTED: "Promoted",
  }[action] || action;
}

function formatTime(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}
