-- enqueue_or_move.lua: first insert, or atomic move between priority queues (ADR-001, S-F3-07/08/10, R-F3-03).
-- KEYS[1] entry:<id>  KEYS[2] tombstone:<id>  KEYS[3..5] queue:HIGH|MEDIUM|LOW  KEYS[6] queue:claimed
-- KEYS[7] outbox:pending  KEYS[8] inbox key ("" = no dedupe: aging, rebuild)
-- ARGV[1] id  ARGV[2] priority  ARGV[3] triageVersion  ARGV[4] requestedAtMs  ARGV[5] reason
-- ARGV[6] outbox record ("" = no PatientEnqueued: rebuild)  ARGV[7] patientId ("" = keep)
-- ARGV[8] symptoms JSON ("" = keep)  ARGV[9] inbox TTL seconds
-- Returns ENQUEUED | MOVED | UNCHANGED | STALE | TOMBSTONED | DUPLICATE | ABSENT
local entry, tombstone, claimed, outbox, inbox = KEYS[1], KEYS[2], KEYS[6], KEYS[7], KEYS[8]
local queues = { HIGH = KEYS[3], MEDIUM = KEYS[4], LOW = KEYS[5] }
local id, priority, version, requestedAt, reason, record, patientId, symptoms, inboxTtl =
  ARGV[1], ARGV[2], tonumber(ARGV[3]), ARGV[4], ARGV[5], ARGV[6], ARGV[7], ARGV[8], ARGV[9]

if inbox ~= '' and redis.call('EXISTS', inbox) == 1 then
  return 'DUPLICATE'
end

local function done(result)
  if inbox ~= '' then
    redis.call('SET', inbox, '1', 'EX', inboxTtl)
  end
  return result
end

-- the outbox record is built in Java; the script fills in what only it knows
local function emit(queue, previousQueue, eventReason)
  if record == '' then
    return
  end
  local r = cjson.decode(record)
  r.envelope.payload.queue = queue
  r.envelope.payload.previousQueue = previousQueue or cjson.null
  r.envelope.payload.reason = eventReason
  redis.call('RPUSH', outbox, cjson.encode(r))
end

-- an assigned or cancelled consultation never comes back (Review Focus 3)
if redis.call('EXISTS', tombstone) == 1 then
  return done('TOMBSTONED')
end

local current = redis.call('HMGET', entry, 'queue', 'triageVersion', 'requestedAt')
local currentQueue, currentVersion, storedRequestedAt = current[1], tonumber(current[2]), current[3]

if not currentQueue then
  if reason == 'AGING' then
    return done('ABSENT') -- removed between the aging scan and this move
  end
  redis.call('HSET', entry, 'queue', priority, 'triageVersion', version, 'requestedAt', requestedAt)
  if patientId ~= '' then
    redis.call('HSET', entry, 'patientId', patientId)
  end
  if symptoms ~= '' then
    redis.call('HSET', entry, 'symptoms', symptoms)
  end
  redis.call('ZADD', queues[priority], requestedAt, id)
  emit(priority, nil, 'INITIAL')
  return done('ENQUEUED')
end

-- rebuild never overwrites what is already there
if record == '' and reason == 'INITIAL' then
  return done('UNCHANGED')
end

-- triageVersion is monotonic; aging re-applies the stored version on purpose
if version < currentVersion or (version == currentVersion and reason ~= 'AGING') then
  return done('STALE')
end
if reason == 'AGING' and currentQueue ~= 'LOW' then
  return done('UNCHANGED') -- aging only promotes LOW to MEDIUM, never to HIGH
end

redis.call('HSET', entry, 'triageVersion', version)
if patientId ~= '' then
  redis.call('HSET', entry, 'patientId', patientId)
end
if symptoms ~= '' then
  redis.call('HSET', entry, 'symptoms', symptoms)
end
if currentQueue == priority then
  return done('UNCHANGED')
end

redis.call('HSET', entry, 'queue', priority)
-- under a claim lease the entry sits in queue:claimed; the new queue applies if the lease expires
if redis.call('ZSCORE', claimed, id) then
  return done('MOVED')
end
local score = storedRequestedAt or requestedAt -- keep the original arrival time (FIFO, ADR-004)
redis.call('ZREM', queues[currentQueue], id)
redis.call('ZADD', queues[priority], score, id)
emit(priority, currentQueue, reason)
return done('MOVED')
