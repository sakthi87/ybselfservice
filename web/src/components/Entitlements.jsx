import { useEffect, useState } from "react";
import { getEntitlement, getEntitlements } from "../api.js";

export function Entitlements({ user }) {
  const [scope, setScope] = useState("mine");
  const [rows, setRows] = useState([]);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getEntitlements(scope === "mine" ? user.userId : null)
      .then((body) => {
        if (cancelled) return;
        setRows(body || []);
        setError("");
      })
      .catch((exception) => {
        if (!cancelled) setError(exception.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [scope, user.userId]);

  async function open(entitlementId) {
    setError("");
    setSelected({ loading: true });
    try {
      const detail = await getEntitlement(entitlementId);
      setSelected(detail);
    } catch (exception) {
      setSelected(null);
      setError(exception.message);
    }
  }

  return (
    <section className="workspace">
      <header className="section-head">
        <div>
          <h2>Entitlements</h2>
          <p>Each database has one entitlement. Tables in that database carry it. Access is requested in another portal. This list shows who is already associated.</p>
        </div>
      </header>
      <div className="scope-bar">
        <div className="scope-switch" role="tablist" aria-label="Entitlement scope">
          <button type="button" className={scope === "mine" ? "scope active" : "scope"} onClick={() => setScope("mine")}>Mine</button>
          <button type="button" className={scope === "all" ? "scope active" : "scope"} onClick={() => setScope("all")}>All databases</button>
        </div>
      </div>
      {error ? <p className="banner error">{error}</p> : null}
      <div className="panel catalog-list">
        <div className="panel-head">
          <h2>Databases</h2>
          <span>{loading ? "Loading" : `${rows.length} found`}</span>
        </div>
        {rows.length === 0 && !loading ? <p className="empty">No entitlements for this view.</p> : null}
        {rows.length > 0 ? (
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Cloud</th>
                  <th>Environment</th>
                  <th>Universe</th>
                  <th>Database</th>
                  <th>Data owner</th>
                  <th>Entitlement</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.databaseId}>
                    <td>{row.cloud}</td>
                    <td>{row.environment}</td>
                    <td>{row.universeCode}</td>
                    <td>{row.databaseName}</td>
                    <td>{row.ownerName || "—"}</td>
                    <td>{row.code || "—"}</td>
                    <td>
                      <button type="button" className="text-button" disabled={!row.entitlementId} onClick={() => open(row.entitlementId)}>View</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
      {selected ? <MemberDialog detail={selected} userId={user.userId} onClose={() => setSelected(null)} /> : null}
    </section>
  );
}

function MemberDialog({ detail, userId, onClose }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="entitlement-title" onClick={(event) => event.stopPropagation()}>
        <div className="panel-head">
          <div>
            <p className="crumb">{detail.loading ? "" : `${detail.cloud} / ${detail.environment} / ${detail.universeCode} / ${detail.databaseName}`}</p>
            <h2 id="entitlement-title">{detail.loading ? "Entitlement" : detail.code}</h2>
          </div>
          <button type="button" className="text-button" onClick={onClose}>Close</button>
        </div>
        {detail.loading ? <p className="empty">Loading association…</p> : (
          <>
            <dl className="facts">
              <div>
                <dt>Name</dt>
                <dd>{detail.name}</dd>
              </div>
              <div>
                <dt>Data owner</dt>
                <dd>{detail.ownerName || "—"}</dd>
              </div>
              <div>
                <dt>Description</dt>
                <dd>{detail.description || "—"}</dd>
              </div>
            </dl>
            <h3>Associated people</h3>
            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>User ID</th>
                    <th>Name</th>
                    <th>Email</th>
                    <th>Association</th>
                  </tr>
                </thead>
                <tbody>
                  {(detail.members || []).map((member) => (
                    <tr key={member.userId} className={member.userId === userId ? "selected" : ""}>
                      <td>{member.userId}</td>
                      <td>{member.fullName}</td>
                      <td>{member.email || "—"}</td>
                      <td>{member.association}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
