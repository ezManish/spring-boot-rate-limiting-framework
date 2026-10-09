-- tc_lease_extend.lua
-- Extends a concurrency permit lease if it still exists.
-- KEYS[1]: concurrency key
-- ARGV[1]: event_id (leaseId)
-- ARGV[2]: lease_ttl_ms

local key = KEYS[1]
local event_id = ARGV[1]
local lease_ttl_ms = tonumber(ARGV[2])

local time_res = redis.call('TIME')
local now_ms = math.floor((tonumber(time_res[1]) * 1000000 + tonumber(time_res[2])) / 1000)

local score = redis.call('ZSCORE', key, event_id)
if score and tonumber(score) > now_ms then
    local new_expiry = now_ms + lease_ttl_ms
    redis.call('ZADD', key, 'XX', new_expiry, event_id)
    redis.call('PEXPIRE', key, lease_ttl_ms * 2)
    return 1
else
    return 0
end
