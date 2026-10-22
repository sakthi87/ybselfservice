import { useEffect, useState } from "react";
import { getNode, getOptions, searchNodes } from "../api.js";

const EMPTY_FILTERS = {
  cloud: "",
  environment: "",
  universe: "",
  region: "",
  role: "",
  nodeName: "",
};

function formatNumber(value, digits = 0) {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString(undefined, {
    maximumFractionDigits: digits,
    minimumFractionDigits: digits,
  });
}

function Meter({ label, value }) {
  const width = Math.max(0, Math.min(value ?? 0, 100));
  return (
    <div className="meter">
      <div className="meter-label">
        <span>{label}</span>
        <span>{value === null || value === undefined ? "—" : `${value}%`}</span>
      </div>
      <div className="meter-track">
        <div style={{ width: `${width}%` }} />
      </div>
    </div>
  );
}

export function NodeCatalog() {
  const [options, setOptions] = useState(null);
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [nodes, setNodes] = useState([]);
  const [capacity, setCapacity] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getOptions()
      .then(setOptions)
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    searchNodes(applied)
      .then((body) => {
        if (cancelled) return;
        setNodes(body.items);
        setCapacity(body.capacity);
        setSelectedId((current) => {
          if (current && body.items.some((item) => item.nodeId === current)) return current;
          return body.items[0]?.nodeId ?? null;
        });
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
  }, [applied]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return undefined;
    }
    let cancelled = false;
    getNode(selectedId)
      .then((body) => {
        if (!cancelled) setDetail(body);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  function updateFilter(name, value) {
    setFilters((current) => ({ ...current, [name]: value }));
  }

  return (
    <section className="workspace">
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          setApplied(filters);
        }}
      >
        <label>
          Cloud
          <select value={filters.cloud} onChange={(event) => updateFilter("cloud", event.target.value)}>
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
            onChange={(event) => updateFilter("environment", event.target.value)}
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
            onChange={(event) => updateFilter("universe", event.target.value)}
          >
            <option value="">All</option>
            {(options?.universes || [])
              .filter((universe) => !filters.cloud || universe.cloud === filters.cloud)
              .filter((universe) => !filters.environment || universe.environment === filters.environment)
              .map((universe) => (
                <option key={universe.code} value={universe.code}>
                  {universe.code}
                </option>
              ))}
          </select>
        </label>
        <label>
          Region
          <select value={filters.region} onChange={(event) => updateFilter("region", event.target.value)}>
            <option value="">All</option>
            {options?.regions.map((region) => (
              <option key={region.code} value={region.code}>
                {region.code}
              </option>
            ))}
          </select>
        </label>
        <label>
          Role
          <select value={filters.role} onChange={(event) => updateFilter("role", event.target.value)}>
            <option value="">All</option>
            <option value="MASTER">MASTER</option>
            <option value="TSERVER">TSERVER</option>
            <option value="MASTER_TSERVER">MASTER_TSERVER</option>
          </select>
        </label>
        <label>
          Node
          <input
            value={filters.nodeName}
            onChange={(event) => updateFilter("nodeName", event.target.value)}
            placeholder="yb-aws-prod"
          />
        </label>
        <button type="submit">Search</button>
      </form>

      {error ? <p className="banner error">{error}</p> : null}

      <div className="stats">
        <article>
          <span>Nodes</span>
          <strong>{loading ? "…" : formatNumber(capacity?.totalNodes)}</strong>
        </article>
        <article>
          <span>CPU cores</span>
          <strong>{loading ? "…" : formatNumber(capacity?.totalCpuCores)}</strong>
        </article>
        <article>
          <span>Memory</span>
          <strong>{loading ? "…" : `${formatNumber(capacity?.totalMemoryGiB)} GiB`}</strong>
        </article>
        <article>
          <span>Storage</span>
          <strong>
            {loading
              ? "…"
              : `${formatNumber(capacity?.usedStorageGiB)} / ${formatNumber(capacity?.totalStorageGiB)} GiB`}
          </strong>
        </article>
      </div>

      <div className="split">
        <div className="panel">
          <div className="panel-head">
            <h2>Nodes</h2>
            <span>{loading ? "Loading" : `${nodes.length} shown`}</span>
          </div>
          <div className="table-wrap">
            <table className="node-table">
              <thead>
                <tr>
                  <th>Node</th>
                  <th>Placement</th>
                  <th>Role</th>
                  <th>CPU</th>
                  <th>Memory</th>
                  <th>Storage</th>
                </tr>
              </thead>
              <tbody>
                {nodes.map((node) => (
                  <tr
                    key={node.nodeId}
                    className={node.nodeId === selectedId ? "selected" : ""}
                    onClick={() => setSelectedId(node.nodeId)}
                  >
                    <td>
                      <strong>{node.name}</strong>
                      <span>{node.privateIp}</span>
                    </td>
                    <td>
                      {node.cloud} {node.environment}
                      <span>
                        {node.universeCode} · {node.region}
                      </span>
                    </td>
                    <td>{node.role}</td>
                    <td>{formatNumber(node.cpuCores)} cores</td>
                    <td>{formatNumber(node.memoryGiB)} GiB</td>
                    <td>
                      {formatNumber(node.storageUsedGiB)} / {formatNumber(node.storageGiB)} GiB
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {nodes.length === 0 && !loading ? <p className="empty">No nodes match these filters.</p> : null}
          </div>
        </div>

        <div className="panel detail">
          {!detail ? (
            <p className="empty">Select a node to see capacity, placement, and configuration.</p>
          ) : (
            <>
              <div className="panel-head">
                <div>
                  <p className="crumb">
                    {detail.cloud} / {detail.environment} / {detail.universeCode} / {detail.region}
                  </p>
                  <h2>{detail.name}</h2>
                </div>
                <span className={`pill ${detail.status.toLowerCase()}`}>{detail.status}</span>
              </div>
              <dl className="facts">
                <div>
                  <dt>Host</dt>
                  <dd>
                    {detail.hostName}
                    <span>{detail.privateIp}</span>
                  </dd>
                </div>
                <div>
                  <dt>Role</dt>
                  <dd>
                    {detail.role}
                    <span>
                      master {detail.masterPort} · tserver {detail.tserverPort}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt>Instance</dt>
                  <dd>
                    {detail.instanceType}
                    <span>{detail.osVersion}</span>
                  </dd>
                </div>
                <div>
                  <dt>Universe</dt>
                  <dd>
                    {detail.universeName}
                    <span>
                      {detail.topology} · RF {detail.replicationFactor} · {detail.yugabyteVersion}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt>Region</dt>
                  <dd>
                    {detail.regionName}
                    <span>
                      {detail.locationType}
                      {detail.leaderPreference ? ` · leader preference ${detail.leaderPreference}` : ""}
                      {detail.isPrimaryRegion ? " · primary" : ""}
                    </span>
                  </dd>
                </div>
              </dl>
              <Meter label="CPU" value={detail.cpuUtilizationPercent} />
              <Meter label="Memory" value={detail.memoryUtilizationPercent} />
              <Meter label="Storage" value={detail.storageUtilizationPercent} />
              <h3>Configuration</h3>
              <pre>{JSON.stringify(detail.configuration, null, 2)}</pre>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
