-- doctor_status.lua: DoctorBecameAvailable / DoctorBecameUnavailable from scheduling-service.
-- KEYS[1] doctor:<id>  KEYS[2] doctors:free  KEYS[3] inbox key
-- ARGV[1] doctorId  ARGV[2] FREE | BUSY | OFFLINE  ARGV[3] version (scheduling's per-doctor version)  ARGV[4] inbox TTL
-- Returns UPDATED | STALE | DUPLICATE
local doctor, free, inbox = KEYS[1], KEYS[2], KEYS[3]
local doctorId, status, version, inboxTtl = ARGV[1], ARGV[2], tonumber(ARGV[3]), ARGV[4]

if inbox ~= '' and redis.call('EXISTS', inbox) == 1 then
  return 'DUPLICATE'
end

local result = 'STALE'
local current = tonumber(redis.call('HGET', doctor, 'version'))
if current == nil or version > current then
  redis.call('HSET', doctor, 'status', status, 'version', version)
  if status == 'FREE' then
    redis.call('SADD', free, doctorId)
  else
    redis.call('SREM', free, doctorId)
  end
  result = 'UPDATED'
end

if inbox ~= '' then
  redis.call('SET', inbox, '1', 'EX', inboxTtl)
end
return result
