import { useState } from 'react'
import type { Consultation, Prescription, SymptomReport, Triage } from './api'
import { api, apiWithStatus } from './api'
import { Card, DEFAULT_SYMPTOMS, Feedback, Id, PRESETS, PriorityBadge, StatusBadge, StatusFlow, SymptomForm, time } from './components'
import { useAction, usePoll, useStoredState } from './hooks'

const ACTIVE = ['REQUESTED', 'TRIAGED', 'WAITING', 'ASSIGNED', 'IN_PROGRESS']

export function PatientView() {
  const [cid, setCid] = useStoredState('medconnect.patient.cid')
  const [symptoms, setSymptoms] = useState<SymptomReport>(DEFAULT_SYMPTOMS)
  const [lastKey, setLastKey] = useState<string | null>(null)
  const request = useAction()

  const consultation = usePoll(() => api<Consultation>('GET', `/consultations/${cid}`), 1500, !!cid, [cid])
  const status = consultation.data?.status
  const active = !status || ACTIVE.includes(status)

  const submit = (key?: string) =>
    request.run(async () => {
      const idempotencyKey = key ?? crypto.randomUUID()
      setLastKey(idempotencyKey)
      const r = await apiWithStatus<{ consultationId: string; status: string }>('POST', '/consultations', {
        body: symptoms,
        idempotencyKey,
      })
      const replay = key && r.data.consultationId === cid
      setCid(r.data.consultationId)
      return replay
        ? `${r.status}: misma consulta devuelta (Idempotency-Key repetida, no se creó otra)`
        : `${r.status} Accepted · estado ${r.data.status}. El triaje corre en segundo plano (evento ConsultationRequested).`
    })

  return (
    <div className="grid">
      <Card
        title="1 · Solicitar consulta"
        hint="POST /consultations → 202 inmediato; triage-service clasifica de forma asíncrona con el rule set v1."
      >
        <div className="presets">
          {PRESETS.map((p) => (
            <button key={p.label} type="button" onClick={() => setSymptoms(p.report)}>
              {p.label}
              <span className="muted"> → {p.expected}</span>
            </button>
          ))}
        </div>
        <SymptomForm value={symptoms} onChange={setSymptoms} />
        <div className="row">
          <button className="primary" disabled={request.busy} onClick={() => submit()}>
            Solicitar consulta
          </button>
          {lastKey && (
            <button disabled={request.busy} onClick={() => submit(lastKey)} title="Demuestra la idempotencia">
              Reenviar con la misma Idempotency-Key
            </button>
          )}
          {cid && !active && (
            <button onClick={() => setCid(null)} className="ghost">
              Olvidar consulta terminada
            </button>
          )}
        </div>
        <Feedback error={request.error} message={request.message} />
      </Card>

      {cid && <ConsultationCard cid={cid} consultation={consultation.data} error={consultation.error} symptoms={symptoms} />}
      <PrescriptionLookup />
    </div>
  )
}

function ConsultationCard({
  cid,
  consultation,
  error,
  symptoms,
}: {
  cid: string
  consultation: Consultation | null
  error: string | null
  symptoms: SymptomReport
}) {
  const status = consultation?.status
  const triage = usePoll(() => api<Triage>('GET', `/triage/consultations/${cid}`), 3000, true, [cid])
  const action = useAction()
  const [joinUrl, setJoinUrl] = useState<string | null>(null)
  const [cancelReason, setCancelReason] = useState('Ya me siento mejor')

  return (
    <Card
      title="2 · Mi consulta"
      hint={
        <>
          Consulta <Id value={cid} /> · cópiala para buscarla desde la pestaña del médico. Se consulta cada 1,5 s.
        </>
      }
    >
      <StatusFlow status={status} />
      <dl className="facts">
        <dt>Estado</dt>
        <dd>
          <StatusBadge status={status} />
        </dd>
        <dt>Prioridad</dt>
        <dd>
          <PriorityBadge priority={consultation?.currentPriority} />
          {triage.data && <span className="muted"> · triageVersion {triage.data.triageVersion} (el paciente no ve la regla)</span>}
        </dd>
        <dt>Médico</dt>
        <dd>
          <Id value={consultation?.doctorId} />
        </dd>
        <dt>Solicitada / asignada / iniciada / completada</dt>
        <dd>
          {time(consultation?.requestedAt)} / {time(consultation?.assignedAt)} / {time(consultation?.startedAt)} / {time(consultation?.completedAt)}
        </dd>
      </dl>
      <Feedback error={error} />

      <div className="row">
        <button
          className="primary"
          disabled={action.busy || !(status === 'ASSIGNED' || status === 'IN_PROGRESS')}
          onClick={() =>
            action.run(async () => {
              const r = await api<{ joinUrl: string }>('POST', `/consultations/${cid}/join`)
              setJoinUrl(r.joinUrl)
              return 'Unido a la videollamada (video-provider simulado). El médico ya puede iniciar.'
            })
          }
        >
          Unirme a la videollamada
        </button>
        <button
          disabled={action.busy || status !== 'WAITING'}
          title="Re-triaje: triage-service vuelve a clasificar y la cola se mueve de forma atómica"
          onClick={() =>
            action.run(async () => {
              const r = await apiWithStatus('PUT', `/consultations/${cid}/symptoms`, { body: symptoms })
              return `${r.status}: síntomas actualizados con el formulario de arriba → re-triaje (triageVersion +1)`
            })
          }
        >
          Actualizar síntomas (re-triaje)
        </button>
      </div>
      <div className="row">
        <input value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} />
        <button
          className="danger"
          disabled={action.busy || !status || !['REQUESTED', 'TRIAGED', 'WAITING', 'ASSIGNED'].includes(status)}
          onClick={() =>
            action.run(async () => {
              await api('POST', `/consultations/${cid}/cancel`, { body: { reason: cancelReason } })
              return 'Cancelada: care-queue la saca de la cola y deja un tombstone de 24 h.'
            })
          }
        >
          Cancelar consulta
        </button>
      </div>
      {joinUrl && (
        <p className="hint">
          joinUrl: <code>{joinUrl}</code>
        </p>
      )}
      <Feedback error={action.error} message={action.message} />
      {status === 'ASSIGNED' && (
        <p className="hint">Si no te unes en 5 minutos, el timer de no-show marca la consulta NO_SHOW y libera al médico.</p>
      )}
    </Card>
  )
}

function PrescriptionLookup() {
  const [id, setId] = useState('')
  const action = useAction()
  const [rx, setRx] = useState<Prescription | null>(null)
  return (
    <Card title="3 · Mi receta" hint="La API no lista recetas por consulta: pega el prescriptionId que te muestra el médico.">
      <div className="row">
        <input value={id} onChange={(e) => setId(e.target.value.trim())} placeholder="prescriptionId" />
        <button
          disabled={!id || action.busy}
          onClick={() =>
            action.run(async () => {
              setRx(await api<Prescription>('GET', `/prescriptions/${id}`))
            })
          }
        >
          Ver receta
        </button>
      </div>
      <Feedback error={action.error} />
      {rx && <PrescriptionView rx={rx} />}
    </Card>
  )
}

export function PrescriptionView({ rx }: { rx: Prescription }) {
  return (
    <div className="rx">
      <p>
        Entrega a farmacia: <StatusBadge status={rx.deliveryStatus} /> · emitida {time(rx.issuedAt)}
      </p>
      <ul>
        {rx.items.map((i, n) => (
          <li key={n}>
            <b>{i.medicationName}</b> {i.dosage}, {i.frequency}, {i.durationDays} días{i.instructions ? ` — ${i.instructions}` : ''}
          </li>
        ))}
      </ul>
      {rx.notes && <p className="muted">{rx.notes}</p>}
    </div>
  )
}
