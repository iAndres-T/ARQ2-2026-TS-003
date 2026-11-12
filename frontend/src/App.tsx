import { useState } from 'react'
import { clearLog, logout } from './api'
import type { LogEntry } from './api'
import { Auth } from './Auth'
import { DoctorView } from './DoctorView'
import { useRequestLog, useSession } from './hooks'
import { PatientView } from './PatientView'

export default function App() {
  const session = useSession()

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo-mark" aria-hidden="true">
            +
          </span>
          <div>
            <h1>MedConnect</h1>
            <p className="muted">Demo de teleconsulta · gateway https://localhost:8443/api/v1</p>
          </div>
        </div>
        {session && (
          <div className="who">
            <span className={`badge role-${session.role}`}>{session.role === 'PATIENT' ? 'Paciente' : 'Médico'}</span>
            <span className="email">{session.email}</span>
            {session.scopes.length > 0 && <span className="muted scopes">{session.scopes.join(' · ')}</span>}
            <button className="ghost" onClick={logout}>
              Salir
            </button>
          </div>
        )}
      </header>

      <div className="layout">
        <main>{!session ? <Auth /> : session.role === 'PATIENT' ? <PatientView /> : <DoctorView />}</main>
        <RequestLog />
      </div>
    </div>
  )
}

function RequestLog() {
  const log = useRequestLog()
  const [open, setOpen] = useState<number | null>(null)

  return (
    <aside className="log">
      <header className="log-head">
        <h2>Peticiones HTTP</h2>
        <button className="ghost" onClick={clearLog} disabled={log.length === 0}>
          Limpiar
        </button>
      </header>
      <p className="hint">Cada llamada al gateway, con su X-Correlation-Id (búscalo en los logs de los servicios).</p>
      {log.length === 0 && <p className="muted">Aún no hay peticiones.</p>}
      <ul>
        {log.map((e) => (
          <LogRow key={e.id} entry={e} open={open === e.id} onToggle={() => setOpen(open === e.id ? null : e.id)} />
        ))}
      </ul>
    </aside>
  )
}

function LogRow({ entry: e, open, onToggle }: { entry: LogEntry; open: boolean; onToggle: () => void }) {
  const cls = e.status >= 500 ? 'err' : e.status >= 400 ? 'warn' : 'ok'
  return (
    <li className={open ? 'open' : ''}>
      <button type="button" className="log-row" onClick={onToggle}>
        <span className={`code ${cls}`}>{e.status}</span>
        <span className="method">{e.method}</span>
        <span className="path">{e.path}</span>
        <span className="muted ms">{e.ms} ms</span>
      </button>
      {open && (
        <div className="log-detail">
          <p>
            <span className="muted">{e.at.toLocaleTimeString()}</span>
            {e.correlationId && (
              <>
                {' '}
                · X-Correlation-Id <code>{e.correlationId}</code>
              </>
            )}
            {e.idempotencyKey && (
              <>
                {' '}
                · Idempotency-Key <code>{e.idempotencyKey}</code>
              </>
            )}
          </p>
          {e.requestBody !== undefined && (
            <>
              <h4>Request</h4>
              <pre>{JSON.stringify(redact(e.requestBody), null, 2)}</pre>
            </>
          )}
          <h4>Response</h4>
          <pre>{e.responseBody == null ? '(vacío)' : JSON.stringify(redact(e.responseBody), null, 2)}</pre>
        </div>
      )}
    </li>
  )
}

/** Hides passwords and shortens tokens so the log can be shown on a projector. */
function redact(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(redact)
  if (v && typeof v === 'object') {
    return Object.fromEntries(
      Object.entries(v).map(([k, val]) => {
        if (k === 'password') return [k, '••••••']
        if (/token$/i.test(k) && typeof val === 'string') return [k, `${val.slice(0, 16)}…`]
        return [k, redact(val)]
      }),
    )
  }
  return v
}
