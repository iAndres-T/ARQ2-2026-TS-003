// Thin client for the MedConnect gateway (https://localhost:8443/api/v1, proxied by Vite).
// The session lives in sessionStorage, so each browser tab can be a different user (patient in one, doctor in another).

export type Role = 'PATIENT' | 'MEDICAL_STAFF'

export interface Session {
  email: string
  role: Role
  accessToken: string
  refreshToken: string
  sub: string
  doctorId?: string
  scopes: string[]
}

export interface Problem {
  status: number
  title?: string
  detail?: string
  errors?: unknown
  correlationId?: string
}

export interface LogEntry {
  id: number
  at: Date
  method: string
  path: string
  status: number
  ms: number
  correlationId: string | null
  idempotencyKey?: string
  requestBody?: unknown
  responseBody?: unknown
}

export class ApiError extends Error {
  readonly problem: Problem
  constructor(problem: Problem) {
    super(`${problem.status} ${problem.title ?? ''}${problem.detail ? `: ${problem.detail}` : ''}`)
    this.problem = problem
  }
}

const SESSION_KEY = 'medconnect.session'
const BASE = '/api/v1'

function readStorage(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string | null) {
  try {
    if (value === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, value)
  } catch {
    // storage blocked: the session just won't survive a reload
  }
}

// --- tiny stores (session + request log) ------------------------------------------------------

type Listener = () => void
const listeners = new Set<Listener>()
export function subscribe(l: Listener) {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}
function emit() {
  listeners.forEach((l) => l())
}

let session: Session | null = (() => {
  const raw = readStorage(SESSION_KEY)
  return raw ? (JSON.parse(raw) as Session) : null
})()

export function getSession() {
  return session
}

function setSession(s: Session | null) {
  session = s
  writeStorage(SESSION_KEY, s ? JSON.stringify(s) : null)
  emit()
}

let log: LogEntry[] = []
let nextLogId = 1
export function getLog() {
  return log
}
export function clearLog() {
  log = []
  emit()
}

// --- JWT ------------------------------------------------------------------------------------------

function decodeJwt(token: string): Record<string, unknown> {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const json = decodeURIComponent(
    atob(part)
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  )
  return JSON.parse(json)
}

function sessionFrom(email: string, tokens: { accessToken: string; refreshToken: string }): Session {
  const claims = decodeJwt(tokens.accessToken)
  return {
    email,
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    role: claims.role as Role,
    sub: claims.sub as string,
    doctorId: (claims.doctorId as string | undefined) ?? undefined,
    scopes: (claims.scopes as string[] | undefined) ?? [],
  }
}

// --- requests -------------------------------------------------------------------------------------

interface Options {
  body?: unknown
  idempotent?: boolean // sends a fresh Idempotency-Key (or `idempotencyKey` when given)
  idempotencyKey?: string
  auth?: boolean
}

async function send(method: string, path: string, opts: Options, retried = false): Promise<{ status: number; data: unknown }> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (opts.auth !== false && session) headers.Authorization = `Bearer ${session.accessToken}`
  const key = opts.idempotencyKey ?? (opts.idempotent ? crypto.randomUUID() : undefined)
  if (key) headers['Idempotency-Key'] = key

  const started = performance.now()
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  })
  const text = await res.text()
  let data: unknown = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }

  log = [
    {
      id: nextLogId++,
      at: new Date(),
      method,
      path,
      status: res.status,
      ms: Math.round(performance.now() - started),
      correlationId: res.headers.get('X-Correlation-Id'),
      idempotencyKey: key,
      requestBody: opts.body,
      responseBody: data,
    },
    ...log,
  ].slice(0, 200)
  emit()

  // expired access token: one transparent refresh (refresh tokens are single use)
  if (res.status === 401 && !retried && opts.auth !== false && session && !path.startsWith('/auth/')) {
    try {
      const s = session
      const tokens = (await send('POST', '/auth/refresh', { body: { refreshToken: s.refreshToken }, auth: false }, true))
        .data as { accessToken: string; refreshToken: string }
      setSession(sessionFrom(s.email, tokens))
      return send(method, path, { ...opts, idempotencyKey: key }, true)
    } catch {
      setSession(null)
    }
  }

  if (!res.ok) {
    const p = (data && typeof data === 'object' ? data : {}) as Partial<Problem>
    throw new ApiError({ ...p, status: res.status, title: p.title ?? res.statusText })
  }
  return { status: res.status, data }
}

export async function api<T = unknown>(method: string, path: string, opts: Options = {}): Promise<T> {
  return (await send(method, path, opts)).data as T
}

/** Same as {@link api} but also returns the HTTP status (needed for 202 / 204). */
export async function apiWithStatus<T = unknown>(method: string, path: string, opts: Options = {}) {
  const r = await send(method, path, opts)
  return { status: r.status, data: r.data as T }
}

export async function login(email: string, password: string) {
  const tokens = await api<{ accessToken: string; refreshToken: string }>('POST', '/auth/login', {
    body: { email, password },
    auth: false,
  })
  setSession(sessionFrom(email, tokens))
}

export async function register(body: { email: string; password: string; fullName: string; birthDate: string }) {
  await api('POST', '/auth/register', { body, auth: false })
  await login(body.email, body.password)
}

export function logout() {
  setSession(null)
}

// --- domain types (docs/spec.md) ------------------------------------------------------------------

export const SYMPTOMS = [
  'CHEST_PAIN',
  'SHORTNESS_OF_BREATH',
  'FEVER',
  'COUGH',
  'HEADACHE',
  'ABDOMINAL_PAIN',
  'SORE_THROAT',
  'NAUSEA_VOMITING',
  'DIZZINESS',
  'OTHER',
] as const

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface SymptomReport {
  mainSymptom: string
  painLevel: number
  symptomDuration: { value: number; unit: 'MINUTES' | 'HOURS' | 'DAYS' }
  age: number
  redFlags: { difficultyBreathing: boolean; lossOfConsciousness: boolean }
  description: string | null
}

export interface Consultation {
  consultationId: string
  patientId: string
  status: string
  currentPriority: Priority | null
  symptoms: SymptomReport
  doctorId: string | null
  requestedAt: string
  assignedAt: string | null
  startedAt: string | null
  completedAt: string | null
  version: number
}

export interface Triage {
  consultationId: string
  priority: Priority
  ruleId?: string | null
  ruleSetVersion?: string
  triageVersion: number
  reason?: string
  overridden?: boolean
}

export interface QueueStats {
  generatedAt: string
  queues: Record<Priority, { waiting: number; oldestWaitSeconds: number }>
  freeDoctors: number
}

export interface Assignment {
  consultationId: string
  patientId: string
  doctorId: string
  priority: Priority
  symptoms: SymptomReport
  assignedAt: string
}

export interface Prescription {
  prescriptionId: string
  consultationId: string
  items: { medicationName: string; dosage: string; frequency: string; durationDays: number; instructions?: string }[]
  notes?: string
  deliveryStatus: string
  issuedAt: string
}

export interface Recording {
  recordingId: string
  status: string
  durationSeconds?: number
  sizeBytes?: number
  downloadUrl?: string | null
  recordedAt?: string
}

export interface AuditEvent {
  eventId: string
  eventType: string
  aggregateId: string
  actorId: string | null
  occurredAt: string
  payload: Record<string, unknown>
}
