import { useState } from 'react'
import { login, register } from './api'
import { Card, Feedback } from './components'
import { useAction } from './hooks'

const DOCTOR_PASSWORD = 'Doctor!Passw0rd1'
const DOCTORS = [
  { email: 'doctor1@medconnect.test', note: 'scheduling:manage · recordings:read · audit:read' },
  { email: 'doctor2@medconnect.test', note: 'sin scopes (útil para mostrar 403)' },
  { email: 'doctor3@medconnect.test', note: 'scheduling:manage' },
]

export function Auth() {
  const action = useAction()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('Patient!Passw0rd1')
  const [fullName, setFullName] = useState('Paciente Demo')
  const [birthDate, setBirthDate] = useState('1968-04-12')

  const randomPatient = () =>
    action.run(async () => {
      const n = Math.random().toString(36).slice(2, 8)
      await register({ email: `paciente.${n}@demo.test`, password: 'Patient!Passw0rd1', fullName: `Paciente ${n}`, birthDate })
    })

  return (
    <div className="auth">
      <Card title="Paciente" hint="Registro en identity-service: devuelve un JWT RS256 con role PATIENT.">
        <button className="primary" disabled={action.busy} onClick={randomPatient}>
          Registrar paciente nuevo (aleatorio)
        </button>
        <details>
          <summary>Registro o login manual</summary>
          <div className="form-grid">
            <label>
              Email
              <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="paciente@demo.test" />
            </label>
            <label>
              Contraseña
              <input value={password} onChange={(e) => setPassword(e.target.value)} />
            </label>
            <label>
              Nombre
              <input value={fullName} onChange={(e) => setFullName(e.target.value)} />
            </label>
            <label>
              Nacimiento
              <input type="date" value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
            </label>
          </div>
          <div className="row">
            <button disabled={action.busy} onClick={() => action.run(() => register({ email, password, fullName, birthDate }))}>
              Registrar
            </button>
            <button disabled={action.busy} onClick={() => action.run(() => login(email, password))}>
              Login
            </button>
          </div>
          <p className="hint">Prueba 5 contraseñas incorrectas seguidas: la cuenta se bloquea (423).</p>
        </details>
      </Card>

      <Card title="Personal médico" hint={`Cuentas sembradas por la migración V2 (contraseña ${DOCTOR_PASSWORD}).`}>
        <div className="stack">
          {DOCTORS.map((d) => (
            <button key={d.email} disabled={action.busy} onClick={() => action.run(() => login(d.email, DOCTOR_PASSWORD))}>
              <b>{d.email}</b>
              <span className="muted"> · {d.note}</span>
            </button>
          ))}
        </div>
      </Card>
      <Feedback error={action.error} />
      <p className="hint">Cada pestaña del navegador guarda su propia sesión: abre una pestaña como paciente y otra como médico.</p>
    </div>
  )
}
