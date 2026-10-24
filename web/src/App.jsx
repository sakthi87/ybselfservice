import { useState } from "react";
import { gql } from "./graphql.js";
import { AuditLog } from "./components/AuditLog.jsx";
import { IntakeFlow } from "./components/IntakeFlow.jsx";
import { Requests } from "./components/Requests.jsx";
import { Entitlements } from "./components/Entitlements.jsx";
import { TableCatalog } from "./components/TableCatalog.jsx";
import { DEMO_USER, demoEnabled, enterDemo, exitDemo } from "./demo/mode.js";
import { initialDemoState } from "./demo/fixture.js";

const PRODUCTS = [
  { id: "yugabyte", label: "YugabyteDB" },
  { id: "api", label: "Data API" },
  { id: "cda", label: "CDA" },
  { id: "kafka", label: "Kafka" },
];

const YUGABYTE_TABS = [
  { id: "catalog", label: "Catalog" },
  { id: "entitlements", label: "Entitlements" },
  { id: "intake", label: "Intake" },
  { id: "requests", label: "Requests" },
  { id: "audit", label: "Audit" },
];

const SESSION_KEY = "odp-session";

function storedSession() {
  if (demoEnabled()) return DEMO_USER;
  const stored = localStorage.getItem(SESSION_KEY);
  if (!stored) return null;
  try {
    const session = JSON.parse(stored);
    return session.userId ? session : null;
  } catch {
    return null;
  }
}

export function App() {
  const [user, setUser] = useState(storedSession);
  const [demo, setDemo] = useState(demoEnabled);
  const [product, setProduct] = useState("yugabyte");
  const [section, setSection] = useState("catalog");

  function startDemo() {
    const session = enterDemo(initialDemoState);
    localStorage.removeItem(SESSION_KEY);
    setDemo(true);
    setUser(session);
    setProduct("yugabyte");
    setSection("catalog");
  }

  function leave() {
    exitDemo();
    localStorage.removeItem(SESSION_KEY);
    setDemo(false);
    setUser(null);
  }

  if (!user) {
    return <Login onSignedIn={(session) => { localStorage.setItem(SESSION_KEY, JSON.stringify(session)); setUser(session); }} onDemo={startDemo} />;
  }

  return (
    <div className="platform">
      <header className="platform-header">
        <div className="brand">
          <span className="brand-mark">ODP</span>
          <h1>Online Data Platform</h1>
        </div>
        <div className="identity">
          <span>{user.userId}</span>
          {demo
            ? <button type="button" onClick={leave}>Exit demo</button>
            : <button type="button" onClick={startDemo}>Demo</button>}
          {!demo && <button type="button" onClick={leave}>Sign out</button>}
        </div>
      </header>
      {demo && <p className="demo-banner">Demo mode uses the sample dataset in this browser. Nothing is sent to the catalog APIs.</p>}
      <div className="platform-body">
        <nav className="product-nav" aria-label="Products">
          {PRODUCTS.map((item) => (
            <button key={item.id} type="button" className={product === item.id ? "product active" : "product"} onClick={() => setProduct(item.id)}>
              {item.label}
            </button>
          ))}
        </nav>
        <main className="product-main">
          {product === "yugabyte" ? (
            <>
              <nav className="section-nav" aria-label="YugabyteDB">
                {YUGABYTE_TABS.map((item) => (
                  <button key={item.id} type="button" className={section === item.id ? "section active" : "section"} onClick={() => setSection(item.id)}>
                    {item.label}
                  </button>
                ))}
              </nav>
              <div className="section-body">
                {section === "catalog" && <TableCatalog user={user} />}
                {section === "entitlements" && <Entitlements user={user} />}
                {section === "intake" && <IntakeFlow user={user} />}
                {section === "requests" && <Requests user={user} />}
                {section === "audit" && <AuditLog user={user} />}
              </div>
            </>
          ) : (
            <Placeholder product={PRODUCTS.find((item) => item.id === product).label} />
          )}
        </main>
      </div>
    </div>
  );
}

function Login({ onSignedIn, onDemo }) {
  const [userId, setUserId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    setError("");
    try {
      const data = await gql(
        "/login/graphql",
        "mutation($userId: String!, $password: String!) { result: signIn(userId: $userId, password: $password) { id userId subjectId } }",
        { userId, password }
      );
      onSignedIn(data.result);
    } catch (exception) {
      setError(exception.message);
    }
  }

  return (
    <div className="login-screen">
      <button type="button" className="demo-launch" onClick={onDemo}>Demo</button>
      <form className="login-card" onSubmit={submit}>
        <span className="brand-mark">ODP</span>
        <h1>Online Data Platform</h1>
        <p>Sign in with your user id and password. Projects, promotions, and audit entries are recorded against this user id.</p>
        <label>User ID<input value={userId} onChange={(event) => setUserId(event.target.value)} autoComplete="username" required /></label>
        <label>Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required /></label>
        {error && <p className="intake-error">{error}</p>}
        <button className="intake-primary" type="submit">Sign in</button>
      </form>
    </div>
  );
}

function Placeholder({ product }) {
  return (
    <section className="placeholder">
      <h2>{product}</h2>
      <p>This product is not enabled in this workspace. YugabyteDB is the active product.</p>
    </section>
  );
}
