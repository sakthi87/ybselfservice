import { useEffect, useState } from "react";
import { gql } from "../graphql.js";

const CARDINALITY = ["HIGH", "MEDIUM", "LOW", "UNKNOWN"];
const FILTER_TYPES = [
  { label: "Equality (=)", value: "EQUALITY" },
  { label: "Range (>, <, BETWEEN)", value: "RANGE" },
  { label: "IN clause", value: "IN" },
];

async function api(query, variables) {
  const data = await gql("/intake/graphql", query, variables);
  return data.result;
}

function emptyEntity() {
  const id = Date.now() + Math.floor(Math.random() * 1000);
  return {
    id,
    entityName: "",
    entityDescription: "",
    sorOfData: "",
    retention: "",
    totalRecord: "",
    recordSizeInBytes: "",
    volumeInGBCurrentYear: "",
    volumeInGB5Years: "",
    csvFields: [],
    fieldAttributes: {},
    accessPatterns: [],
  };
}

export function DesignTable({ schemaId, schemaName, onCreated, changeRequest }) {
  const [entities, setEntities] = useState([emptyEntity()]);
  const [expanded, setExpanded] = useState(null);
  const [tabs, setTabs] = useState({});
  const [results, setResults] = useState({});
  const [apiRows, setApiRows] = useState([]);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  useEffect(() => {
    const patterns = [];
    entities.forEach((entity) => {
      (entity.accessPatterns || []).forEach((pattern) => {
        if (pattern.name && pattern.id) {
          patterns.push({
            entityId: entity.id,
            patternId: pattern.id,
            accessPattern: pattern.name,
            description: pattern.description || "",
          });
        }
      });
    });
    setApiRows((rows) => {
      const kept = new Map();
      rows.forEach((row) => {
        if (row.patternId && row.entityId) {
          kept.set(`${row.entityId}_${row.patternId}`, row);
        }
      });
      return patterns.map((pattern) => {
        const existing = kept.get(`${pattern.entityId}_${pattern.patternId}`);
        return existing
          ? { ...existing, accessPattern: pattern.accessPattern, description: pattern.description }
          : { id: `${pattern.entityId}_${pattern.patternId}`, ...pattern, averageTPS: "", peakTPS: "", slaInMs: "" };
      });
    });
  }, [entities]);

  function updateEntity(id, field, value) {
    setEntities((current) => current.map((entity) => (entity.id === id ? { ...entity, [field]: value } : entity)));
  }

  async function uploadCsv(entityId, file) {
    const text = await file.text();
    const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0);
    if (lines.length < 2) {
      throw new Error("CSV requires a header row and at least one data row.");
    }
    const headers = lines[0].split(",").map((header) => header.trim().toLowerCase());
    const nameIndex = headers.findIndex((header) => header.includes("column") || header.includes("field"));
    const typeIndex = headers.findIndex((header) => header.includes("type"));
    const descriptionIndex = headers.findIndex((header) => header.includes("description"));
    if (nameIndex === -1 || typeIndex === -1) {
      throw new Error("CSV must include column_name and data_type columns.");
    }
    const fields = lines.slice(1).map((line) => line.split(",").map((value) => value.trim())).filter((values) => values[nameIndex]).map((values) => ({
      name: values[nameIndex],
      dataType: values[typeIndex] || "text",
      description: descriptionIndex === -1 ? "" : values[descriptionIndex] || "",
    }));
    const attributes = {};
    fields.forEach((field) => {
      attributes[field.name] = {
        cardinality: "UNKNOWN",
        businessKey: false,
        mutable: false,
        tenantField: false,
        timeField: /time|date|timestamp/i.test(field.dataType),
      };
    });
    setEntities((current) => current.map((entity) => (
      entity.id === entityId ? { ...entity, csvFields: fields, fieldAttributes: attributes } : entity
    )));
  }

  function updateAttribute(entityId, fieldName, attribute, value) {
    setEntities((current) => current.map((entity) => {
      if (entity.id !== entityId) return entity;
      return {
        ...entity,
        fieldAttributes: {
          ...entity.fieldAttributes,
          [fieldName]: { ...entity.fieldAttributes[fieldName], [attribute]: value },
        },
      };
    }));
  }

  function updatePattern(entityId, patternId, field, value) {
    setEntities((current) => current.map((entity) => {
      if (entity.id !== entityId) return entity;
      return {
        ...entity,
        accessPatterns: entity.accessPatterns.map((pattern) => (
          pattern.id === patternId ? { ...pattern, [field]: value } : pattern
        )),
      };
    }));
  }

  function updatePatternField(entityId, patternId, index, field, value) {
    setEntities((current) => current.map((entity) => {
      if (entity.id !== entityId) return entity;
      return {
        ...entity,
        accessPatterns: entity.accessPatterns.map((pattern) => (
          pattern.id === patternId
            ? { ...pattern, fields: pattern.fields.map((item, position) => (position === index ? { ...item, [field]: value } : item)) }
            : pattern
        )),
      };
    }));
  }

  function modelPayload(entity) {
    const tenant = Object.values(entity.fieldAttributes).some((attribute) => attribute.tenantField);
    const timeSeries = Object.values(entity.fieldAttributes).some((attribute) => attribute.timeField);
    return {
      entityName: entity.entityName || "unnamed",
      description: entity.entityDescription,
      keyspace: schemaName,
      fields: entity.csvFields.map((field) => ({
        name: field.name,
        dataType: field.dataType,
        description: field.description,
        cardinality: entity.fieldAttributes[field.name]?.cardinality || "UNKNOWN",
        businessKey: entity.fieldAttributes[field.name]?.businessKey || false,
        mutable: entity.fieldAttributes[field.name]?.mutable || false,
        tenantField: entity.fieldAttributes[field.name]?.tenantField || false,
        timeField: entity.fieldAttributes[field.name]?.timeField || false,
      })),
      accessPatterns: entity.accessPatterns.map((pattern) => ({
        name: pattern.name,
        description: pattern.description,
        cardinality: pattern.cardinality || "UNKNOWN",
        filters: pattern.fields.filter((field) => field.field).map((field) => ({ field: field.field, type: field.filterType })),
        sortFields: pattern.fields.filter((field) => field.field && field.sortDirection).map((field) => ({
          field: field.field,
          direction: field.sortDirection,
        })),
      })),
      constraints: {
        partitionSizeExpectation: "MEDIUM",
        queryVolume: "READ_HEAVY",
        multiTenant: tenant,
        timeSeries,
        keyspace: schemaName,
      },
    };
  }

  async function generate(entityId) {
    const entity = entities.find((item) => item.id === entityId);
    setError("");
    if (!entity.entityName.trim()) {
      setError("Entity name is required");
      return;
    }
    if (entity.csvFields.length === 0) {
      setError("Upload a CSV schema first");
      return;
    }
    if (entity.accessPatterns.length === 0 || !entity.accessPatterns.some((pattern) => pattern.fields.some((field) => field.field))) {
      setError("Add at least one access pattern with a filter");
      return;
    }
    try {
      const response = await api("mutation($input: JSON!) { result: generateModel(input: $input) }", {
        input: { entities: [modelPayload(entity)] },
      });
      setResults((current) => ({ ...current, [entityId]: response.entities[0] }));
      setTabs((current) => ({ ...current, [entityId]: "ddl" }));
    } catch (exception) {
      setError(exception.message);
    }
  }

  async function create(entity) {
    setError("");
    const rows = apiRows.filter((row) => row.entityId === entity.id);
    const average = rows.map((row) => Number(row.averageTPS) || 0).reduce((sum, value) => sum + value, 0);
    const peak = rows.reduce((max, row) => Math.max(max, Number(row.peakTPS) || 0), 0);
    try {
      const created = await api("mutation($schemaId: ID!, $input: JSON!) { result: designTable(schemaId: $schemaId, input: $input) }", {
        schemaId,
        input: {
          ...modelPayload(entity),
          entityDescription: entity.entityDescription,
          sorOfData: entity.sorOfData,
          retention: entity.retention,
          totalRecord: entity.totalRecord,
          recordSizeInBytes: entity.recordSizeInBytes,
          volumeInGBCurrentYear: entity.volumeInGBCurrentYear,
          volumeInGB5Years: entity.volumeInGB5Years,
          averageTps: average || "",
          peakTps: peak || "",
          requestId: changeRequest?.requestId || "",
          requestDescription: changeRequest?.description || "",
        },
      });
      setMessage(`Created ${created.schema_name}.${created.table_name} in DEV and registered its owner and table context.`);
      if (onCreated) {
        await onCreated();
      }
    } catch (exception) {
      setError(exception.message);
    }
  }

  return (
    <div className="design-table">
      {error && <p className="intake-error">{error}</p>}
      {message && <p className="intake-message">{message}</p>}
      <div className="design-heading">
        <h3>YugabyteDB Details</h3>
        <button type="button" onClick={() => {
          const entity = emptyEntity();
          setEntities((current) => [...current, entity]);
          setExpanded(entity.id);
          setTabs((current) => ({ ...current, [entity.id]: "csv" }));
        }}>+ Add Entity</button>
      </div>
      <p className="hint">Same design steps as the Cassandra intake. The schema {schemaName} is the keyspace, and Generate DDL writes YugabyteDB SQL.</p>
      {entities.map((entity) => {
        const tab = tabs[entity.id] || "csv";
        const result = results[entity.id];
        return (
          <article key={entity.id} className="design-entity">
            <div className="entity-fields">
              {[
                ["entityName", "Entity name", "text"],
                ["entityDescription", "Entity description", "text"],
                ["sorOfData", "SOR of data", "text"],
                ["retention", "Retention", "text"],
                ["totalRecord", "Total records", "number"],
                ["recordSizeInBytes", "Record size (bytes)", "number"],
                ["volumeInGBCurrentYear", "Volume GB (current year)", "number"],
                ["volumeInGB5Years", "Volume GB (5 years)", "number"],
              ].map(([field, label, type]) => (
                <label key={field}>{label}
                  <input type={type} value={entity[field]} onChange={(event) => updateEntity(entity.id, field, event.target.value)} />
                </label>
              ))}
              <div className="entity-actions">
                <button type="button" className="intake-link" onClick={() => setExpanded(expanded === entity.id ? null : entity.id)}>{expanded === entity.id ? "Hide model" : "Model"}</button>
                <button type="button" className="intake-link" onClick={() => setEntities((current) => current.filter((item) => item.id !== entity.id))}>Remove</button>
              </div>
            </div>
            {expanded === entity.id && (
              <div>
                <div className="tabs">
                  <button type="button" className={tab === "csv" ? "tab active" : "tab"} onClick={() => setTabs({ ...tabs, [entity.id]: "csv" })}>CSV Schema</button>
                  <button type="button" className={tab === "fields" ? "tab active" : "tab"} disabled={entity.csvFields.length === 0} onClick={() => setTabs({ ...tabs, [entity.id]: "fields" })}>Field Attributes</button>
                  <button type="button" className={tab === "patterns" ? "tab active" : "tab"} disabled={entity.csvFields.length === 0} onClick={() => setTabs({ ...tabs, [entity.id]: "patterns" })}>Access Patterns</button>
                  <button type="button" className={tab === "ddl" ? "tab active" : "tab"} disabled={!result} onClick={() => setTabs({ ...tabs, [entity.id]: "ddl" })}>DDL Script</button>
                  <button type="button" className="intake-primary" disabled={entity.csvFields.length === 0 || entity.accessPatterns.length === 0} onClick={() => generate(entity.id)}>Generate DDL</button>
                </div>
                {tab === "csv" && (
                  <div>
                    <label className="intake-primary file-button">
                      Upload CSV
                      <input type="file" accept=".csv" hidden onChange={(event) => {
                        const file = event.target.files?.[0];
                        if (file) uploadCsv(entity.id, file).catch((exception) => setError(exception.message));
                      }} />
                    </label>
                    <p className="hint">Header columns: column_name, data_type, description.</p>
                    {entity.csvFields.length > 0 && (
                      <table className="design-grid">
                        <thead><tr><th>Field Name</th><th>Data Type</th><th>Description</th></tr></thead>
                        <tbody>
                          {entity.csvFields.map((field) => (
                            <tr key={field.name}><td>{field.name}</td><td>{field.dataType}</td><td>{field.description}</td></tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
                {tab === "fields" && (
                  <table className="design-grid">
                    <thead>
                      <tr><th>Field</th><th>Cardinality</th><th>Business Key</th><th>Mutable</th><th>Tenant Field</th><th>Time Field</th></tr>
                    </thead>
                    <tbody>
                      {entity.csvFields.map((field) => {
                        const attribute = entity.fieldAttributes[field.name] || {};
                        return (
                          <tr key={field.name}>
                            <td>{field.name}</td>
                            <td>
                              <select value={attribute.cardinality || "UNKNOWN"} onChange={(event) => updateAttribute(entity.id, field.name, "cardinality", event.target.value)}>
                                {CARDINALITY.map((option) => <option key={option}>{option}</option>)}
                              </select>
                            </td>
                            {["businessKey", "mutable", "tenantField", "timeField"].map((name) => (
                              <td key={name}>
                                <input type="checkbox" checked={Boolean(attribute[name])} onChange={(event) => updateAttribute(entity.id, field.name, name, event.target.checked)} />
                              </td>
                            ))}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
                {tab === "patterns" && (
                  <div>
                    <button type="button" onClick={() => updateEntity(entity.id, "accessPatterns", [...entity.accessPatterns, { id: Date.now(), name: "", description: "", fields: [], cardinality: "UNKNOWN" }])}>+ Add Pattern</button>
                    <table className="design-grid">
                      <thead><tr><th>Name</th><th>Description</th><th>Fields</th><th>Cardinality</th><th></th></tr></thead>
                      <tbody>
                        {entity.accessPatterns.map((pattern) => (
                          <tr key={pattern.id}>
                            <td><input value={pattern.name} onChange={(event) => updatePattern(entity.id, pattern.id, "name", event.target.value)} /></td>
                            <td><input value={pattern.description} onChange={(event) => updatePattern(entity.id, pattern.id, "description", event.target.value)} /></td>
                            <td>
                              {pattern.fields.map((field, index) => (
                                <div key={index} className="pattern-field">
                                  <select value={field.field} onChange={(event) => updatePatternField(entity.id, pattern.id, index, "field", event.target.value)}>
                                    <option value="">Field</option>
                                    {entity.csvFields.map((item) => <option key={item.name}>{item.name}</option>)}
                                  </select>
                                  <select value={field.filterType} onChange={(event) => updatePatternField(entity.id, pattern.id, index, "filterType", event.target.value)}>
                                    {FILTER_TYPES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                                  </select>
                                  <select value={field.sortDirection || ""} onChange={(event) => updatePatternField(entity.id, pattern.id, index, "sortDirection", event.target.value)}>
                                    <option value="">No sort</option>
                                    <option value="ASC">ASC</option>
                                    <option value="DESC">DESC</option>
                                  </select>
                                  <button type="button" onClick={() => updatePattern(entity.id, pattern.id, "fields", pattern.fields.filter((_, position) => position !== index))}>×</button>
                                </div>
                              ))}
                              <button type="button" onClick={() => updatePattern(entity.id, pattern.id, "fields", [...pattern.fields, { field: "", filterType: "EQUALITY", sortDirection: "" }])}>+ Field</button>
                            </td>
                            <td>
                              <select value={pattern.cardinality} onChange={(event) => updatePattern(entity.id, pattern.id, "cardinality", event.target.value)}>
                                {CARDINALITY.map((option) => <option key={option}>{option}</option>)}
                              </select>
                            </td>
                            <td><button type="button" onClick={() => updateEntity(entity.id, "accessPatterns", entity.accessPatterns.filter((item) => item.id !== pattern.id))}>×</button></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {tab === "ddl" && result && (
                  <div>
                    <p><strong>Partition key:</strong> {result.partitionKey?.join(", ") || "None"}</p>
                    <p><strong>Clustering keys:</strong> {result.clusteringKeys?.map((key) => `${key.field} ${key.order}`).join(", ") || "None"}</p>
                    <p><strong>Indexes:</strong> {result.indexes?.map((index) => index.field).join(", ") || "None"}</p>
                    <pre className="intake-ddl">{result.createTableCql}</pre>
                    {(result.indexCql || []).map((statement) => <pre key={statement} className="intake-ddl">{statement}</pre>)}
                    {(result.warnings || []).length > 0 && (
                      <ul>{result.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
                    )}
                    <button type="button" className="intake-primary" onClick={() => create(entity)}>Create table in DEV</button>
                  </div>
                )}
              </div>
            )}
          </article>
        );
      })}
      <h3>API Details</h3>
      <p className="hint">Average and peak TPS are stored as estimated TPS on the table context.</p>
      {apiRows.length === 0 && <p className="hint">Access patterns appear here after you name them.</p>}
      {apiRows.length > 0 && (
        <table className="design-grid">
          <thead><tr><th>Access Pattern</th><th>Description</th><th>Average TPS</th><th>Peak TPS</th><th>SLA in ms</th></tr></thead>
          <tbody>
            {apiRows.map((row) => (
              <tr key={row.id}>
                <td><input value={row.accessPattern} readOnly /></td>
                <td><input value={row.description} readOnly /></td>
                {["averageTPS", "peakTPS", "slaInMs"].map((field) => (
                  <td key={field}>
                    <input type="number" value={row[field]} onChange={(event) => setApiRows((rows) => rows.map((item) => item.id === row.id ? { ...item, [field]: event.target.value } : item))} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
