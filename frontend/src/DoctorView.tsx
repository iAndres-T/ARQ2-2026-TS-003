import { useState } from 'react'
import type { Assignment, AuditEvent, Consultation, Prescription, Priority, QueueStats, Recording, Triage } from './api'
import { api, apiWithStatus } from './api'
import { Card, Feedback, Id, PriorityBadge, StatusBadge, StatusFlow, time } from './components'
import { useAction, usePoll, useSession, useStoredState } from './hooks'
import { PrescriptionView } from './PatientView'

interface Availability {
  doctorId: string
  status: string
  since?: string
}

export function DoctorView() {
  const session = useSession()!
  const [cid, setCid] = useStoredState('medconnect.doctor.cid')

  return (
    <div className="grid">
      <ShiftCard doctorId={session.doctorId!} />
      <QueueCard onClaimed={setCid} />
      <LookupCard current={cid} onPick={setCid} />
      {cid && <ConsultationWorkbench cid={cid} doctorId={session.doctorId!} scopes={session.scopes} />}
    </div>
  )
}

function ShiftCard({ doctorId }: { doctorId: string }) {
  const action = useAction()
  const availability = usePoll(() => api<{ items: Availability[] }>('GET', '/doctors/availability'), 3000)
  const mine = availability.data?.items.find((d) => d.doctorId === doctorId)

  const createShift = () =>
    action.run(async () => {
      const startsAt = new Date(Date.now() - 60_000).toISOString()
      const endsAt = new Date(Date.now() + 8 * 3600_000).toISOString()
      await api('POST', '/shifts', { body: { doctorId, startsAt, endsAt } })
      return 'Turno creado (ahora → +8 h). Ya puedes ponerte FREE.'
    })

  const setStatus = (status: 'FREE' | 'OFFLINE') =>
    action.run(async () => {
      await api('PUT', '/doctors/me/availability', { body: { status } })
      return `${status}: scheduling-service publica DoctorBecame${status === 'FREE' ? 'Available' : 'Unavailable'} y care-queue lo aplica en Redis.`
    })

  return (
    <Card
      title="1 · Turno y disponibilidad"
      hint="scheduling-service. Sin turno activo no puedes ponerte FREE (409); sin scope scheduling:manage no puedes crear turnos (403)."
    >
      <p>
        Mi estado: <StatusBadge status={mine?.status} /> {mine?.since && <span className="muted">desde {time(mine.since)}</span>}
      </p>
      <div className="row">
        <button disabled={action.busy} onClick={createShift}>
          Crear turno ahora → +8 h
        </button>
        <button className="primary" disabled={action.busy} onClick={() => setStatus('FREE')}>
          Ponerme FREE
        </button>
        <button disabled={action.busy} onClick={() => setStatus('OFFLINE')}>
          OFFLINE
        </button>
      </div>
      <Feedback error={action.error ?? availability.error} message={action.message} />
      {availability.data && (
        <table className="mini">
          <tbody>
            {availability.data.items.map((d) => (
              <tr key={d.doctorId}>
                <td>
                  <Id value={d.doctorId} />
                </td>
                <td>
                  <StatusBadge status={d.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}

function QueueCard({ onClaimed }: { onClaimed: (cid: string) => void }) {
  const stats = usePoll(() => api<QueueStats>('GET', '/queue/stats'), 2000)
  const action = useAction()
  const [lastKey, setLastKey] = useState<string | null>(null)
  const [assignment, setAssignment] = useState<Assignment | null>(null)

  const claim = (key?: string) =>
    action.run(async () => {
      const idempotencyKey = key ?? crypto.randomUUID()
      setLastKey(idempotencyKey)
      const r = await apiWithStatus<Assignment>('POST', '/queue/claims', { idempotencyKey })
      if (r.status === 204) {
        setAssignment(null)
        return '204: colas vacías, no hay pacientes esperando.'
      }
      setAssignment(r.data)
      onClaimed(r.data.consultationId)
      return key ? '201 repetido: misma asignación (la respuesta se guardó con la Idempotency-Key, no se tomó otro paciente).' : '201: paciente asignado.'
    })

  const max = Math.max(1, ...(['HIGH', 'MEDIUM', 'LOW'] as Priority[]).map((p) => stats.data?.queues[p].waiting ?? 0))

  return (
    <Card
      title="2 · Colas de prioridad"
      hint="care-queue-service sobre Redis (sorted sets por llegada). El claim toma el más antiguo de HIGH, luego MEDIUM, luego LOW, en un script Lua atómico."
    >
      <div className="bars">
        {(['HIGH', 'MEDIUM', 'LOW'] as Priority[]).map((p) => {
          const q = stats.data?.queues[p]
          return (
            <div key={p} className="bar-row">
              <PriorityBadge priority={p} />
              <div className="bar-track">
                <div className={`bar prio-${p}`} style={{ width: `${((q?.waiting ?? 0) / max) * 100}%` }} />
              </div>
              <span className="num">{q?.waiting ?? '—'}</span>
              <span className="muted">{q && q.waiting > 0 ? `más antiguo ${q.oldestWaitSeconds}s` : ''}</span>
            </div>
          )
        })}
      </div>
      <p className="muted">Médicos FREE: {stats.data?.freeDoctors ?? '—'}</p>
      <div className="row">
        <button className="primary" disabled={action.busy} onClick={() => claim()}>
          Tomar siguiente paciente
        </button>
        {lastKey && (
          <button disabled={action.busy} onClick={() => claim(lastKey)}>
            Reenviar con la misma Idempotency-Key
          </button>
        )}
      </div>
      <Feedback error={action.error ?? stats.error} message={action.message} />
      {assignment && (
        <p>
          Asignado: <Id value={assignment.consultationId} /> <PriorityBadge priority={assignment.priority} />{' '}
          <span className="muted">
            {assignment.symptoms?.mainSymptom}, dolor {assignment.symptoms?.painLevel}
          </span>
        </p>
      )}
    </Card>
  )
}

function LookupCard({ current, onPick }: { current: string | null; onPick: (cid: string | null) => void }) {
  const [value, setValue] = useState('')
  return (
    <Card title="Buscar consulta" hint="Pega el id que muestra la pestaña del paciente para ver su triaje, cambiar la prioridad o revisar la auditoría.">
      <div className="row">
        <input value={value} onChange={(e) => setValue(e.target.value.trim())} placeholder="consultationId" />
        <button disabled={!value} onClick={() => onPick(value)}>
          Abrir
        </button>
        {current && (
          <button className="ghost" onClick={() => onPick(null)}>
            Cerrar la actual
          </button>
        )}
      </div>
    </Card>
  )
}

function ConsultationWorkbench({ cid, doctorId, scopes }: { cid: string; doctorId: string; scopes: string[] }) {
  const consultation = usePoll(() => api<Consultation>('GET', `/consultations/${cid}`), 1500, true, [cid])
  const triage = usePoll(() => api<Triage>('GET', `/triage/consultations/${cid}`), 3000, true, [cid])
  const c = consultation.data
  const mine = c?.doctorId === doctorId

  return (
    <>
      <Card
        title="3 · Consulta en curso"
        hint={
          <>
            Consulta <Id value={cid} /> {mine ? '(asignada a ti)' : '(no asignada a ti: solo lectura y override)'}
          </>
        }
      >
        <StatusFlow status={c?.status} />
        <dl className="facts">
          <dt>Estado</dt>
          <dd>
            <StatusBadge status={c?.status} />
          </dd>
          <dt>Síntomas</dt>
          <dd>
            {c ? `${c.symptoms.mainSymptom}, dolor ${c.symptoms.painLevel}, ${c.symptoms.age} años` : '—'}
            {c?.symptoms.redFlags.difficultyBreathing && <span className="badge prio-HIGH">dificultad respiratoria</span>}
            {c?.symptoms.redFlags.lossOfConsciousness && <span className="badge prio-HIGH">pérdida de conciencia</span>}
          </dd>
          <dt>Triaje</dt>
          <dd>
            <PriorityBadge priority={triage.data?.priority} /> {triage.data?.ruleId && <code>{triage.data.ruleId}</code>}{' '}
            <span className="muted">
              v{triage.data?.triageVersion} · {(triage.data as Triage & { source?: string })?.source}
            </span>
          </dd>
        </dl>
        <Feedback error={consultation.error} />
        <Override cid={cid} status={c?.status} current={triage.data?.priority} />
        {mine && <Lifecycle cid={cid} status={c?.status} />}
      </Card>
      {mine && <PrescriptionCard cid={cid} status={c?.status} />}
      <RecordingCard cid={cid} status={c?.status} canRead={scopes.includes('recordings:read')} />
      <AuditCard cid={cid} canRead={scopes.includes('audit:read')} />
    </>
  )
}

function Override({ cid, status, current }: { cid: string; status?: string; current?: Priority }) {
  const action = useAction()
  const [priority, setPriority] = useState<Priority>('HIGH')
  const [reason, setReason] = useState('Llamada de control: el dolor empeoró')
  return (
    <div className="subsection">
      <h3>Cambiar prioridad (override)</h3>
      <div className="row">
        <select value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
          <option>HIGH</option>
          <option>MEDIUM</option>
          <option>LOW</option>
        </select>
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="motivo (5-500 caracteres)" />
        <button
          disabled={action.busy}
          onClick={() =>
            action.run(async () => {
              await api('POST', `/triage/consultations/${cid}/override`, { body: { newPriority: priority, reason } })
              return `Override ${current ?? '?'} → ${priority}: PriorityOverridden mueve al paciente de cola conservando su hora de llegada.`
            })
          }
        >
          Aplicar
        </button>
      </div>
      <p className="hint">Solo mientras espera: con la consulta IN_PROGRESS responde 409. Misma prioridad → 409. Motivo corto → 422.</p>
      {status && <Feedback error={action.error} message={action.message} />}
    </div>
  )
}

function Lifecycle({ cid, status }: { cid: string; status?: string }) {
  const action = useAction()
  const [summary, setSummary] = useState('Sospecha de angina. Remitido a cardiología.')
  const [codes, setCodes] = useState('I20.9')
  return (
    <div className="subsection">
      <h3>Atención</h3>
      <div className="row">
        <button
          className="primary"
          disabled={action.busy || status !== 'ASSIGNED'}
          onClick={() =>
            action.run(async () => {
              await api('POST', `/consultations/${cid}/start`)
              return 'IN_PROGRESS (el paciente debe haberse unido antes; si no, 409).'
            })
          }
        >
          Iniciar consulta
        </button>
      </div>
      <div className="row">
        <input value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="resumen clínico" />
        <input value={codes} onChange={(e) => setCodes(e.target.value)} placeholder="códigos CIE-10, separados por coma" className="short" />
        <button
          disabled={action.busy || status !== 'IN_PROGRESS'}
          onClick={() =>
            action.run(async () => {
              await api('POST', `/consultations/${cid}/complete`, {
                body: { clinicalSummary: summary, diagnosisCodes: codes.split(',').map((s) => s.trim()).filter(Boolean) },
              })
              return 'COMPLETED: vuelves a FREE automáticamente y recording-service empieza a procesar la grabación.'
            })
          }
        >
          Completar
        </button>
      </div>
      <Feedback error={action.error} message={action.message} />
    </div>
  )
}

function PrescriptionCard({ cid, status }: { cid: string; status?: string }) {
  const action = useAction()
  const [rxId, setRxId] = useStoredState(`medconnect.rx.${cid}`)
  const [med, setMed] = useState({ medicationName: 'Aspirina', dosage: '100 mg', frequency: 'una vez al día', durationDays: 30, instructions: 'Después del desayuno' })
  const rx = usePoll(() => api<Prescription>('GET', `/prescriptions/${rxId}`), 2000, !!rxId, [rxId])

  return (
    <Card
      title="4 · Receta"
      hint="prescription-service guarda la receta y el evento en la misma transacción (outbox, ADR-002). La entrega a farmacia es asíncrona: si la farmacia está caída, la receta igual queda registrada."
    >
      <div className="form-grid">
        <label>
          Medicamento
          <input value={med.medicationName} onChange={(e) => setMed({ ...med, medicationName: e.target.value })} />
        </label>
        <label>
          Dosis
          <input value={med.dosage} onChange={(e) => setMed({ ...med, dosage: e.target.value })} />
        </label>
        <label>
          Frecuencia
          <input value={med.frequency} onChange={(e) => setMed({ ...med, frequency: e.target.value })} />
        </label>
        <label>
          Días
          <input type="number" min={1} value={med.durationDays} onChange={(e) => setMed({ ...med, durationDays: Number(e.target.value) })} />
        </label>
      </div>
      <div className="row">
        <button
          className="primary"
          disabled={action.busy || status !== 'IN_PROGRESS'}
          onClick={() =>
            action.run(async () => {
              const r = await apiWithStatus<{ prescriptionId: string }>('POST', '/prescriptions', {
                body: { consultationId: cid, items: [med], notes: 'Emitida desde la demo' },
                idempotent: true,
              })
              setRxId(r.data.prescriptionId)
              return `${r.status}: receta emitida.`
            })
          }
        >
          Emitir receta
        </button>
        <span className="hint">Solo con la consulta IN_PROGRESS (si no, 409).</span>
      </div>
      <Feedback error={action.error ?? rx.error} message={action.message} />
      {rxId && (
        <p>
          prescriptionId: <Id value={rxId} /> <span className="muted">(cópialo en la pestaña del paciente)</span>
        </p>
      )}
      {rx.data && <PrescriptionView rx={rx.data} />}
    </Card>
  )
}

function RecordingCard({ cid, status, canRead }: { cid: string; status?: string; canRead: boolean }) {
  const enabled = status === 'COMPLETED'
  const rec = usePoll(() => api<Recording>('GET', `/consultations/${cid}/recording`), 3000, enabled, [cid])
  return (
    <Card
      title="5 · Grabación"
      hint="recording-service (bulkhead, ADR-003): recibe el webhook firmado del proveedor de video, guarda en MinIO y entrega una URL prefirmada de 300 s."
    >
      {!enabled && <p className="muted">Disponible cuando la consulta esté COMPLETED.</p>}
      {enabled && !canRead && <p className="hint">Tu usuario no tiene el scope recordings:read: esperas un 403.</p>}
      {rec.data && (
        <>
          <p>
            Estado: <StatusBadge status={rec.data.status} />{' '}
            {rec.data.durationSeconds != null && <span className="muted">{rec.data.durationSeconds}s</span>}{' '}
            {rec.data.sizeBytes != null && <span className="muted">· {(rec.data.sizeBytes / 1024).toFixed(1)} KB</span>}
          </p>
          {rec.data.downloadUrl && (
            <a href={rec.data.downloadUrl} target="_blank" rel="noreferrer">
              Descargar (URL prefirmada de MinIO)
            </a>
          )}
        </>
      )}
      <Feedback error={rec.error} />
    </Card>
  )
}

function AuditCard({ cid, canRead }: { cid: string; canRead: boolean }) {
  const audit = usePoll(() => api<{ items: AuditEvent[] }>('GET', `/audit/events?aggregateId=${cid}&limit=100`), 3000, true, [cid])
  const items = [...(audit.data?.items ?? [])].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))
  return (
    <Card
      title="6 · Auditoría"
      hint="audit-service consume todos los eventos y los guarda en una tabla append-only (solo INSERT/SELECT). Es la historia completa de la consulta."
    >
      {!canRead && <p className="hint">Tu usuario no tiene el scope audit:read: esperas un 403.</p>}
      <ol className="timeline">
        {items.map((e) => (
          <li key={e.eventId}>
            <span className="muted">{time(e.occurredAt)}</span> <b>{e.eventType}</b>{' '}
            <span className="muted">{summarize(e)}</span>
          </li>
        ))}
      </ol>
      <Feedback error={audit.error} />
    </Card>
  )
}

function summarize(e: AuditEvent) {
  const p = e.payload ?? {}
  const parts = ['priority', 'queue', 'previousQueue', 'newPriority', 'previousPriority', 'reason', 'ruleId', 'status', 'deliveryStatus']
    .filter((k) => p[k] != null && p[k] !== '')
    .map((k) => `${k}=${String(p[k])}`)
  if (e.actorId) parts.push(`actor=${e.actorId.slice(0, 8)}`)
  return parts.join(' · ')
}
