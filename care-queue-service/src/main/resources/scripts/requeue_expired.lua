-- requeue_expired.lua: a claim lease expired without confirm (the service died in between, S-F4-12):
-- the patient goes back to its queue with its original score and the doctor is FREE again.
-- KEYS[1] entry:<id>  KEYS[2] queue:claimed  KEYS[3..5] queue:HIGH|MEDIUM|LOW  KEYS[6] outbox:pending
-- KEYS[7] doctors:free
-- ARGV[1] id  ARGV[2] now ms  ARGV[3] outbox record (queue and triageVersion filled in here)
-- Returns REQUEUED | NOT_EXPIRED | ABSENT
local entry, claimed, outbox, free = KEYS[1], KEYS[2], KEYS[6], KEYS[7]
local queues = { HIGH = KEYS[3], MEDIUM = KEYS[4], LOW = KEYS[5] }
local id, now, record = ARGV[1], tonumber(ARGV[2]), ARGV[3]

local leaseEnd = redis.call('ZSCORE', claimed, id)
if not leaseEnd or tonumber(leaseEnd) > now then
  return 'NOT_EXPIRED' -- confirmed, cancelled or renewed meanwhile
end
redis.call('ZREM', claimed, id)

local e = redis.call('HMGET', entry, 'queue', 'triageVersion', 'requestedAt', 'claimedBy')
local queue, triageVersion, requestedAt, doctorId = e[1], e[2], e[3], e[4]

if doctorId then
  local doctor = 'doctor:' .. doctorId
  if redis.call('HGET', doctor, 'status') == 'CLAIMING' then
    redis.call('HSET', doctor, 'status', 'FREE')
    redis.call('SADD', free, doctorId)
  end
  redis.call('HDEL', entry, 'claimedBy')
end

if not queue or not requestedAt then
  return 'ABSENT'
end

redis.call('ZADD', queues[queue], requestedAt, id)
local r = cjson.decode(record)
r.envelope.payload.queue = queue
r.envelope.payload.triageVersion = tonumber(triageVersion)
r.envelope.payload.previousQueue = cjson.null
r.envelope.payload.reason = 'INITIAL'
r.envelope.aggregateVersion = tonumber(triageVersion)
redis.call('RPUSH', outbox, cjson.encode(r))
return 'REQUEUED'
