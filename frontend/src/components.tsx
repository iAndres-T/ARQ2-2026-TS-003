import type { ReactNode } from 'react'
import type { Priority, SymptomReport } from './api'
import { SYMPTOMS } from './api'

export function Card({ title, hint, children, actions }: { title: string; hint?: ReactNode; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head">
        <div>
          <h2>{title}</h2>
          {hint && <p className="hint">{hint}</p>}
        </div>
        {actions && <div className="card-actions">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

export function Feedback({ error, message }: { error?: string | null; message?: string | null }) {
  if (error) return <p className="feedback error">{error}</p>
  if (message) return <p className="feedback ok">{message}</p>
  return null
}

export function PriorityBadge({ priority }: { priority: Priority | null | undefined }) {
  if (!priority) return <span className="badge">—</span>
  return <span className={`badge prio-${priority}`}>{priority}</span>
}

export function StatusBadge({ status }: { status: string | null | undefined }) {
  return <span className={`badge status-${status ?? 'none'}`}>{status ?? '—'}</span>
}

export function Id({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>
  const copy = () => {
    navigator.clipboard?.writeText(value).catch(() => undefined)
  }
  return (
    <button type="button" className="id" onClick={copy} title="Copiar">
      {value.slice(0, 8)}…
    </button>
  )
}

const FLOW = ['REQUESTED', 'TRIAGED', 'WAITING', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED']

export function StatusFlow({ status }: { status: string | null | undefined }) {
  const terminal = status === 'CANCELLED' || status === 'NO_SHOW'
  const idx = status ? FLOW.indexOf(status) : -1
  return (
    <ol className="flow">
      {FLOW.map((s, i) => (
        <li key={s} className={i < idx ? 'done' : i === idx ? 'current' : ''}>
          {s}
        </li>
      ))}
      {terminal && <li className="current terminal">{status}</li>}
    </ol>
  )
}

export function time(iso: string | null | undefined) {
  return iso ? new Date(iso).toLocaleTimeString() : '—'
}

export const DEFAULT_SYMPTOMS: SymptomReport = {
  mainSymptom: 'CHEST_PAIN',
  painLevel: 9,
  symptomDuration: { value: 30, unit: 'MINUTES' },
  age: 58,
  redFlags: { difficultyBreathing: false, lossOfConsciousness: false },
  description: null,
}

/** Presets that hit each rule of triage rule set v1 (see the Postman folder 04). */
export const PRESETS: { label: string; expected: string; report: SymptomReport }[] = [
  { label: 'Dolor de pecho', expected: 'HIGH · R2_CRITICAL_SYMPTOM', report: DEFAULT_SYMPTOMS },
  {
    label: 'Tos + dificultad respiratoria',
    expected: 'HIGH · R1_RED_FLAG',
    report: { ...DEFAULT_SYMPTOMS, mainSymptom: 'COUGH', painLevel: 3, age: 40, redFlags: { difficultyBreathing: true, lossOfConsciousness: false } },
  },
  {
    label: 'Fiebre con dolor 8',
    expected: 'MEDIUM · R3_FEVER_HIGH_PAIN',
    report: { ...DEFAULT_SYMPTOMS, mainSymptom: 'FEVER', painLevel: 8, age: 30, symptomDuration: { value: 2, unit: 'DAYS' } },
  },
  {
    label: 'Tos leve',
    expected: 'LOW · R4_DEFAULT',
    report: { ...DEFAULT_SYMPTOMS, mainSymptom: 'COUGH', painLevel: 2, age: 25, symptomDuration: { value: 3, unit: 'DAYS' } },
  },
]

export function SymptomForm({ value, onChange }: { value: SymptomReport; onChange: (v: SymptomReport) => void }) {
  return (
    <div className="form-grid">
      <label>
        Síntoma principal
        <select value={value.mainSymptom} onChange={(e) => onChange({ ...value, mainSymptom: e.target.value })}>
          {SYMPTOMS.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
      </label>
      <label>
        Dolor (1-10): <b>{value.painLevel}</b>
        <input type="range" min={1} max={10} value={value.painLevel} onChange={(e) => onChange({ ...value, painLevel: Number(e.target.value) })} />
      </label>
      <label>
        Duración
        <span className="row">
          <input
            type="number"
            min={1}
            value={value.symptomDuration.value}
            onChange={(e) => onChange({ ...value, symptomDuration: { ...value.symptomDuration, value: Number(e.target.value) } })}
          />
          <select
            value={value.symptomDuration.unit}
            onChange={(e) =>
              onChange({ ...value, symptomDuration: { ...value.symptomDuration, unit: e.target.value as SymptomReport['symptomDuration']['unit'] } })
            }
          >
            <option>MINUTES</option>
            <option>HOURS</option>
            <option>DAYS</option>
          </select>
        </span>
      </label>
      <label>
        Edad
        <input type="number" min={0} max={130} value={value.age} onChange={(e) => onChange({ ...value, age: Number(e.target.value) })} />
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={value.redFlags.difficultyBreathing}
          onChange={(e) => onChange({ ...value, redFlags: { ...value.redFlags, difficultyBreathing: e.target.checked } })}
        />
        Dificultad para respirar
      </label>
      <label className="check">
        <input
          type="checkbox"
          checked={value.redFlags.lossOfConsciousness}
          onChange={(e) => onChange({ ...value, redFlags: { ...value.redFlags, lossOfConsciousness: e.target.checked } })}
        />
        Pérdida de conciencia
      </label>
      <label className="wide">
        Descripción
        <input value={value.description ?? ''} onChange={(e) => onChange({ ...value, description: e.target.value || null })} />
      </label>
    </div>
  )
}
