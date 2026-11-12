-- claim.lua: a FREE doctor pops the oldest patient of the highest non-empty queue under a lease (ADR-004).
-- KEYS[1] doctor:<id>  KEYS[2] doctors:free  KEYS[3..5] queue:HIGH|MEDIUM|LOW  KEYS[6] queue:claimed
-- ARGV[1] doctorId  ARGV[2] now ms  ARGV[3] lease ms
-- Returns consultationId | nil (all queues empty) | NOT_FREE
local doctor, free, claimed = KEYS[1], KEYS[2], KEYS[6]
local doctorId, now, lease = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])

-- CLAIMING also counts as not free: one doctor firing parallel claims gets one patient
if redis.call('HGET', doctor, 'status') ~= 'FREE' or redis.call('SISMEMBER', free, doctorId) == 0 then
  return 'NOT_FREE'
end

for i = 3, 5 do
  local popped = redis.call('ZPOPMIN', KEYS[i])
  if popped[1] then
    local id = popped[1]
    redis.call('ZADD', claimed, now + lease, id)
    redis.call('HSET', 'entry:' .. id, 'claimedBy', doctorId)
    -- local mark only: the version stays scheduling-service's
    redis.call('HSET', doctor, 'status', 'CLAIMING')
    redis.call('SREM', free, doctorId)
    return id
  end
end
return nil
