import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { ApiError, getLog, getSession, subscribe } from './api'

export function useSession() {
  return useSyncExternalStore(subscribe, getSession)
}

export function useRequestLog() {
  return useSyncExternalStore(subscribe, getLog)
}

/** Polls `load` every `ms` while `enabled`; returns the last value and error. */
export function usePoll<T>(load: () => Promise<T>, ms: number, enabled = true, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => {
    if (!enabled) return
    let active = true
    let timer: ReturnType<typeof setTimeout>
    const tick = async () => {
      try {
        const v = await loadRef.current()
        if (active) {
          setData(v)
          setError(null)
        }
      } catch (e) {
        if (active) setError(errorText(e))
      }
      if (active) timer = setTimeout(tick, ms)
    }
    tick()
    return () => {
      active = false
      clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, enabled, ...deps])

  return { data, error, setData }
}

/** Wraps an async action with busy / error / result state for buttons. */
export function useAction() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const run = useCallback(async (fn: () => Promise<string | void>) => {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const msg = await fn()
      if (msg) setMessage(msg)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }, [])

  return { busy, error, message, run }
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    const p = e.problem
    return `${p.status} · ${p.title ?? 'Error'}${p.detail ? ` — ${p.detail}` : ''}`
  }
  return e instanceof Error ? e.message : String(e)
}

export function useStoredState(key: string, initial: string | null = null) {
  const [value, setValue] = useState<string | null>(() => {
    try {
      return sessionStorage.getItem(key) ?? initial
    } catch {
      return initial
    }
  })
  const set = useCallback(
    (v: string | null) => {
      setValue(v)
      try {
        if (v === null) sessionStorage.removeItem(key)
        else sessionStorage.setItem(key, v)
      } catch {
        // storage blocked: keep it in memory only
      }
    },
    [key],
  )
  return [value, set] as const
}
