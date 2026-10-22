import { Fragment, useEffect, useState } from "react";
import { gql } from "../graphql.js";

async function api(query, variables) {
  const data = await gql("/intake/graphql", query, variables);
  return data.result;
}

export function Requests({ user }) {
  const [rows, setRows] = useState([]);
  const [selected, setSelected] = useState(null);
  const [dialog, setDialog] = useState(null);
  const [expanded, setExpanded] = useState({});
  const [checked, setChecked] = useState([]);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  async function load() {
    const body = await api("query($userId: String!) { result: requests(userId: $userId) }", { userId: user.userId });
    setRows(body);
    setSelected((current) => (body.some((item) => item.project_id === current) ? current : null));
  }

  useEffect(() => {
    load().catch((exception) => setError(exception.message));
  }, [user.userId]);

  function openDialog(projectId, mode) {
    const project = rows.find((item) => item.project_id === projectId);
    const ids = (project?.databases || []).flatMap((database) => (database.tableList || []).map((table) => table.table_id));
    setSelected(projectId);
    setDialog(mode);
    if (mode === "migrate") setChecked(ids);
  }

  function closeDialog() {
    setSelected(null);
    setDialog(null);
  }

  async function promote(environment, tableIds) {
    setError("");
    setMessage("");
    try {
      const result = await api("mutation($projectId: ID!, $input: JSON!) { result: promote(projectId: $projectId, input: $input) }", {
        projectId: selected,
        input: { environment, tableIds },
      });
      const names = (result.promotions || []).map((item) => `${item.tables} → ${item.targetDatabase}`).join("; ");
      setMessage(`Promoted to ${result.environment}. ${names}`);
      await load();
    } catch (exception) {
      setError(exception.message);
    }
  }

  const groups = Object.entries(rows.reduce((grouped, row) => {
    const key = row.edai_req_id || "No ODP Req ID";
    grouped[key] = grouped[key] || [];
    grouped[key].push(row);
    return grouped;
  }, {}));
  const detail = rows.find((item) => item.project_id === selected);
  const tableRows = (detail?.databases || []).flatMap((database) =>
    (database.tableList || []).map((table) => ({ ...table, database_name: database.database_name })));

  return (
    <section className="requests">
      <header className="section-head">
        <div>
          <h2>Requests</h2>
          <p>Intakes filed by {user.userId}. Expand a project to see its change requests. User Inputs shows the intake form. Migrate promotes its tables.</p>
        </div>
        <button type="button" className="text-button" onClick={() => load().catch((exception) => setError(exception.message))}>Refresh</button>
      </header>
      {error && <p className="intake-error">{error}</p>}
      {message && <p className="intake-message">{message}</p>}
      {rows.length === 0 ? <p className="empty">No intakes yet. Submit one from the Intake tab.</p> : groups.map(([carId, projects]) => (
        <section key={carId} className="car-group">
          <h3>ODP Req ID {carId}</h3>
          <p className="hint">{projects.length} project request{projects.length === 1 ? "" : "s"} · {projects[0].domain || "No domain"}</p>
          <table className="data-table">
            <thead>
              <tr>
                <th></th>
                <th>ODP Req ID</th>
                <th>Project</th>
                <th>Business line</th>
                <th>Submitted</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {projects.map((row) => {
                const open = Boolean(expanded[row.project_id]);
                const changes = row.changes || [];
                return (
                  <Fragment key={row.project_id}>
                    <tr className="intake-row">
                      <td>
                        <button
                          type="button"
                          className="text-button"
                          aria-expanded={open}
                          onClick={() => setExpanded((current) => ({ ...current, [row.project_id]: !current[row.project_id] }))}
                        >
                          {open ? "▾" : "▸"}
                        </button>
                      </td>
                      <td>{row.edai_req_id || "—"}</td>
                      <td>{row.project_name}</td>
                      <td>{row.business_line || "—"}</td>
                      <td>{formatWhen(row.created_at)}</td>
                      <td className="row-actions">
                        <button type="button" className="intake-link" onClick={() => openDialog(row.project_id, "view")}>User Inputs</button>
                        <button type="button" className="intake-link" onClick={() => openDialog(row.project_id, "migrate")}>Migrate</button>
                      </td>
                    </tr>
                    {open && (
                      <tr className="change-tree">
                        <td></td>
                        <td colSpan={5}>
                          {changes.length === 0 && <p className="hint">No change requests for this project yet.</p>}
                          {changes.length > 0 && (
                            <table className="data-table">
                              <thead>
                                <tr>
                                  <th>Change request</th>
                                  <th>Description</th>
                                  <th>Submitted</th>
                                </tr>
                              </thead>
                              <tbody>
                                {changes.map((change) => (
                                  <tr key={change.request_id}>
                                    <td>{change.request_id}</td>
                                    <td>{change.description}</td>
                                    <td>{formatWhen(change.created_at)}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </section>
      ))}
      {detail && dialog === "view" && (
        <div className="promote-backdrop" onClick={closeDialog}>
          <div className="panel promote-dialog" role="dialog" aria-labelledby="view-title" onClick={(event) => event.stopPropagation()}>
            <div className="section-head">
              <h3 id="view-title">{detail.edai_req_id || "No ODP Req ID"} · {detail.project_name}</h3>
              <button type="button" className="text-button" onClick={closeDialog}>Close</button>
            </div>
            <dl className="detail-grid">
              <dt>ODP Req ID</dt><dd>{shown(detail.edai_req_id)}</dd>
              <dt>Business line</dt><dd>{shown(detail.business_line)}</dd>
              <dt>Fund type</dt><dd>{shown(detail.fund_type)}</dd>
              <dt>Fund value</dt><dd>{shown(detail.fund_value)}</dd>
              <dt>Domain</dt><dd>{shown(detail.domain)}</dd>
              <dt>Sub domain</dt><dd>{shown(detail.sub_domain)}</dd>
              <dt>Project name</dt><dd>{shown(detail.project_name)}</dd>
              <dt>Project description</dt><dd>{shown(detail.description)}</dd>
              <dt>CAR ID</dt><dd>{shown(detail.car_id)}</dd>
              <dt>CAR name</dt><dd>{shown(detail.car_name)}</dd>
              <dt>Technical owner</dt><dd>{shown(detail.tech_owner_email)}</dd>
              <dt>Business line owner</dt><dd>{shown(detail.business_line_owner)}</dd>
              <dt>Developer contact</dt><dd>{shown(detail.developer_email)}</dd>
              <dt>Support assignment group</dt><dd>{shown(detail.support_assignment_group)}</dd>
              <dt>Exp. dev date</dt><dd>{formatDate(detail.exp_dev_date)}</dd>
              <dt>Exp. IT date</dt><dd>{formatDate(detail.exp_it_date)}</dd>
              <dt>Exp. UAT date</dt><dd>{formatDate(detail.uat_date)}</dd>
              <dt>Exp. prod date</dt><dd>{formatDate(detail.prod_date)}</dd>
              <dt>Submitted</dt><dd>{formatWhen(detail.created_at)}</dd>
            </dl>
          </div>
        </div>
      )}
      {detail && dialog === "migrate" && (
        <div className="promote-backdrop" onClick={closeDialog}>
        <div className="panel promote-dialog" role="dialog" aria-labelledby="promote-title" onClick={(event) => event.stopPropagation()}>
          <div className="section-head">
            <h3 id="promote-title">{detail.edai_req_id ? `${detail.edai_req_id} · ${detail.project_name}` : detail.project_name}</h3>
            <button type="button" className="text-button" onClick={closeDialog}>Close</button>
          </div>
          {error && <p className="intake-error">{error}</p>}
          {message && <p className="intake-message">{message}</p>}
          {tableRows.length === 0 && <p className="hint">No tables yet. Create them from Intake, then return here to promote.</p>}
          {tableRows.length > 0 && (
            <>
              <div className="form-actions">
                <button type="button" className="text-button" onClick={() => setChecked(tableRows.map((table) => table.table_id))}>Select all</button>
                <button type="button" className="text-button" onClick={() => setChecked([])}>Clear</button>
              </div>
              <table className="data-table">
                <thead><tr><th></th><th>Database</th><th>Schema</th><th>Table</th></tr></thead>
                <tbody>
                  {tableRows.map((table) => (
                    <tr key={table.table_id}>
                      <td>
                        <input
                          type="checkbox"
                          checked={checked.includes(table.table_id)}
                          onChange={(event) => setChecked((current) => event.target.checked ? [...current, table.table_id] : current.filter((id) => id !== table.table_id))}
                        />
                      </td>
                      <td>{table.database_name}</td>
                      <td>{table.schema_name}</td>
                      <td>{table.table_name}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="promotion-actions">
                {["IT", "UAT", "PROD"].map((environment) => (
                  <button key={environment} type="button" disabled={checked.length === 0} onClick={() => promote(environment, checked)}>
                    Promote selected to {environment}
                  </button>
                ))}
              </div>
              <p className="hint">{checked.length} of {tableRows.length} tables selected. Select all to migrate the whole request, or leave one table selected to migrate only that table.</p>
            </>
          )}
        </div>
        </div>
      )}
    </section>
  );
}

function shown(value) {
  return value === null || value === undefined || value === "" ? "—" : String(value);
}

function formatDate(value) {
  if (!value) return "—";
  const match = String(value).match(/^(\d{4}-\d{2}-\d{2})/);
  return match ? match[1] : String(value);
}

function formatWhen(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}
