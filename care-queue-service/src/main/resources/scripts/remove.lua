-- remove.lua: ConsultationCancelled removes the consultation from every queue and tombstones it (S-F4-10).
-- KEYS[1] entry:<id>  KEYS[2] tombstone:<id>  KEYS[3..5] queue:HIGH|MEDIUM|LOW  KEYS[6] queue:claimed
-- KEYS[7] inbox key  KEYS[8] doctors:free
-- ARGV[1] id  ARGV[2] tombstone TTL seconds  ARGV[3] inbox TTL seconds
-- Returns REMOVED | ABSENT | DUPLICATE
local entry, tombstone, claimed, inbox, free = KEYS[1], KEYS[2], KEYS[6], KEYS[7], KEYS[8]
local id, tombstoneTtl, inboxTtl = ARGV[1], ARGV[2], ARGV[3]

if inbox ~= '' and redis.call('EXISTS', inbox) == 1 then
  return 'DUPLICATE'
end

local removed = 0
for i = 3, 5 do
  removed = removed + redis.call('ZREM', KEYS[i], id)
end

-- cancelled during a claim lease: the claiming request will see CANCELLED; free its doctor now in case that
-- request already died (ClaimIT.cancelledDuringLeaseIsNotAssigned)
if redis.call('ZREM', claimed, id) == 1 then
  removed = removed + 1
  local doctorId = redis.call('HGET', entry, 'claimedBy')
  if doctorId then
    local doctor = 'doctor:' .. doctorId
    if redis.call('HGET', doctor, 'status') == 'CLAIMING' then
      redis.call('HSET', doctor, 'status', 'FREE')
      redis.call('SADD', free, doctorId)
    end
  end
end

removed = removed + redis.call('DEL', entry)
redis.call('SET', tombstone, 'CANCELLED', 'EX', tombstoneTtl)
if inbox ~= '' then
  redis.call('SET', inbox, '1', 'EX', inboxTtl)
end
if removed > 0 then
  return 'REMOVED'
end
return 'ABSENT'
