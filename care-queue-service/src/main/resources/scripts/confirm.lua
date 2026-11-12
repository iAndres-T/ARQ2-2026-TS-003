-- confirm.lua: turns a claim lease into an assignment and queues PatientAssigned in the same step.
-- KEYS[1] entry:<id>  KEYS[2] tombstone:<id>  KEYS[3] queue:claimed  KEYS[4] outbox:pending  KEYS[5] doctor:<id>
-- KEYS[6] doctors:free  KEYS[7] idem:<doctorId>:<key>
-- ARGV[1] id  ARGV[2] doctorId  ARGV[3] outbox record  ARGV[4] tombstone TTL  ARGV[5] idempotent response
-- ARGV[6] idempotency TTL
-- Returns CONFIRMED | CANCELLED | EXPIRED
local entry, tombstone, claimed, outbox, doctor, free, idem =
  KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6], KEYS[7]
local id, doctorId, record, tombstoneTtl, idemValue, idemTtl = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5], ARGV[6]

if not redis.call('ZSCORE', claimed, id) then
  -- cancelled (remove.lua) or lease recovered (requeue_expired.lua) meanwhile: the doctor may claim again
  if redis.call('HGET', doctor, 'status') == 'CLAIMING' then
    redis.call('HSET', doctor, 'status', 'FREE')
    redis.call('SADD', free, doctorId)
  end
  if redis.call('EXISTS', tombstone) == 1 then
    return 'CANCELLED'
  end
  return 'EXPIRED'
end

redis.call('ZREM', claimed, id)
redis.call('DEL', entry)
redis.call('SET', tombstone, 'ASSIGNED', 'EX', tombstoneTtl)
redis.call('HSET', doctor, 'status', 'BUSY')
redis.call('SREM', free, doctorId)
redis.call('RPUSH', outbox, record)
redis.call('SET', idem, idemValue, 'EX', idemTtl)
return 'CONFIRMED'
