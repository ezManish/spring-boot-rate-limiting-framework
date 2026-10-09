-- tc_decide.lua (Version 1)
-- Normative implementation of atomic multi-rule rate limiting and concurrency leasing.
-- Returns 8 integers: [allowed, binding_rule, reason, remaining, limit, reset_at_ms, retry_after_ms, now_ms]

local script_version = tonumber(ARGV[1])
if script_version ~= 1 then
    return redis.error_reply("TC_ERR_VERSION expected 1 got " .. tostring(script_version))
end

local n = tonumber(ARGV[2])
local has_conc = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])
local event_id = ARGV[5]

if n < 0 or n > 8 or (has_conc ~= 0 and has_conc ~= 1) or cost < 1 then
    return redis.error_reply("TC_ERR_ARGS invalid base arguments")
end

-- Time source: Redis TIME
local time_res = redis.call('TIME')
local now_sec = tonumber(time_res[1])
local now_usec = tonumber(time_res[2])
local now_us = now_sec * 1000000 + now_usec
local now_ms = math.floor(now_us / 1000)

local rule_eval = {}
local all_allowed = true
local failing_rule_idx = 0
local max_deny_retry = 0

local arg_idx = 6

-- =========================================================================
-- Phase 1: Evaluate Rate Rules
-- =========================================================================
for i = 1, n do
    local algo_id = tonumber(ARGV[arg_idx])
    local requests = tonumber(ARGV[arg_idx + 1])
    local window_ms = tonumber(ARGV[arg_idx + 2])
    local burst = tonumber(ARGV[arg_idx + 3])
    -- ARGV[arg_idx + 4] is reserved
    arg_idx = arg_idx + 5

    local key = KEYS[i]
    local allowed = 0
    local remaining = 0
    local limit = (algo_id == 1 or algo_id == 2) and burst or requests
    local reset_at_ms = 0
    local retry_after_ms = 0
    local ttl_ms = 0
    local write_fn = nil

    if algo_id == 1 then
        -- TOKEN_BUCKET
        local cap = burst * window_ms
        local raw_state = redis.call('HMGET', key, 't', 'ts')
        local t = cap
        local ts = now_ms
        if raw_state[1] and raw_state[2] then
            t = tonumber(raw_state[1])
            ts = tonumber(raw_state[2])
        end

        local elapsed = math.min(math.max(0, now_ms - ts), math.ceil(cap / requests))
        t = math.min(cap, t + elapsed * requests)
        ts = now_ms

        local need = cost * window_ms
        if cost > burst then
            allowed = 0
            retry_after_ms = window_ms
        elseif t >= need then
            allowed = 1
            t = t - need
            retry_after_ms = 0
        else
            allowed = 0
            retry_after_ms = math.ceil((need - t) / requests)
        end

        remaining = math.floor(t / window_ms)
        reset_at_ms = now_ms + math.ceil((cap - t) / requests)
        ttl_ms = math.ceil(cap / requests) + 1000

        local final_t = t
        local final_ts = ts
        write_fn = function()
            redis.call('HMSET', key, 't', final_t, 'ts', final_ts)
            redis.call('PEXPIRE', key, ttl_ms)
        end

    elseif algo_id == 2 then
        -- GCRA (LEAKY_BUCKET)
        local w_us = window_ms * 1000
        local T = math.ceil(w_us / requests)
        local tau = burst * T

        local raw_tat = redis.call('GET', key)
        local tat = now_us
        if raw_tat then
            tat = tonumber(raw_tat)
        end

        local tat0 = math.max(tat, now_us)
        local new_tat = tat0 + cost * T

        if new_tat - now_us <= tau then
            allowed = 1
            tat = new_tat
            retry_after_ms = 0
        else
            allowed = 0
            retry_after_ms = math.ceil((new_tat - tau - now_us) / 1000)
        end

        local final_tat = (allowed == 1) and new_tat or tat0
        remaining = math.max(0, math.floor((tau - (final_tat - now_us)) / T))
        reset_at_ms = math.ceil(final_tat / 1000)
        ttl_ms = math.ceil(math.max(final_tat - now_us, 0) / 1000) + 1000

        local to_write_tat = tat
        write_fn = function()
            redis.call('SET', key, to_write_tat)
            redis.call('PEXPIRE', key, ttl_ms)
        end

    elseif algo_id == 3 then
        -- FIXED_WINDOW
        local ws = math.floor(now_ms / window_ms) * window_ms
        local raw = redis.call('HMGET', key, 'c', 'ws')
        local count = 0
        if raw[1] and raw[2] and tonumber(raw[2]) == ws then
            count = tonumber(raw[1])
        end

        if count + cost <= requests then
            allowed = 1
            count = count + cost
            retry_after_ms = 0
        else
            allowed = 0
            retry_after_ms = ws + window_ms - now_ms
        end

        remaining = math.max(0, requests - count)
        reset_at_ms = ws + window_ms
        ttl_ms = (ws + window_ms - now_ms) + 1000

        local final_count = count
        write_fn = function()
            redis.call('HMSET', key, 'c', final_count, 'ws', ws)
            redis.call('PEXPIRE', key, ttl_ms)
        end

    elseif algo_id == 4 then
        -- SLIDING_WINDOW_COUNTER
        local ws_now = math.floor(now_ms / window_ms) * window_ms
        local raw = redis.call('HMGET', key, 'p', 'c', 'ws')
        local prev = 0
        local curr = 0
        if raw[3] then
            local stored_ws = tonumber(raw[3])
            if stored_ws == ws_now then
                prev = tonumber(raw[1]) or 0
                curr = tonumber(raw[2]) or 0
            elseif stored_ws == ws_now - window_ms then
                prev = tonumber(raw[2]) or 0
                curr = 0
            end
        end

        local e = now_ms - ws_now
        local est = math.floor(prev * (window_ms - e) / window_ms) + curr

        if est + cost <= requests then
            allowed = 1
            curr = curr + cost
            retry_after_ms = 0
        else
            allowed = 0
            local m = requests - curr - cost
            if m >= 0 and prev > 0 then
                retry_after_ms = math.max(1, (window_ms - math.ceil((m + 1) * window_ms / prev) + 1) - e)
            else
                retry_after_ms = ws_now + window_ms - now_ms
            end
        end

        remaining = math.max(0, requests - (allowed == 1 and (est + cost) or est))
        reset_at_ms = ws_now + window_ms
        ttl_ms = 2 * window_ms + 1000

        local final_prev = prev
        local final_curr = curr
        write_fn = function()
            redis.call('HMSET', key, 'p', final_prev, 'c', final_curr, 'ws', ws_now)
            redis.call('PEXPIRE', key, ttl_ms)
        end

    elseif algo_id == 5 then
        -- SLIDING_WINDOW_LOG
        -- Permitted maintenance write: remove expired entries
        redis.call('ZREMRANGEBYSCORE', key, '-inf', now_ms - window_ms)
        local count = redis.call('ZCARD', key)

        if count + cost <= requests then
            allowed = 1
            retry_after_ms = 0
        else
            allowed = 0
            local k = count + cost - requests
            local scores = redis.call('ZRANGE', key, k - 1, k - 1, 'WITHSCORES')
            if #scores >= 2 then
                retry_after_ms = tonumber(scores[2]) + window_ms - now_ms
            else
                retry_after_ms = window_ms
            end
        end

        remaining = math.max(0, requests - count - (allowed == 1 and cost or 0))
        local newest = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES')
        local newest_score = (#newest >= 2) and tonumber(newest[2]) or now_ms
        reset_at_ms = newest_score + window_ms
        ttl_ms = window_ms + 1000

        write_fn = function()
            for c_idx = 1, cost do
                redis.call('ZADD', key, now_ms, event_id .. ':' .. c_idx)
            end
            redis.call('PEXPIRE', key, ttl_ms)
        end
    else
        return redis.error_reply("TC_ERR_ARGS unknown algorithm id " .. tostring(algo_id))
    end

    rule_eval[i] = {
        allowed = allowed,
        remaining = remaining,
        limit = limit,
        reset_at_ms = reset_at_ms,
        retry_after_ms = retry_after_ms,
        write_fn = write_fn
    }

    if allowed == 0 then
        all_allowed = false
        if failing_rule_idx == 0 or retry_after_ms > max_deny_retry then
            failing_rule_idx = i
            max_deny_retry = retry_after_ms
        end
    end
end

-- =========================================================================
-- Concurrency Evaluation
-- =========================================================================
local conc_allowed = 1
local conc_retry = 0
local conc_max = 0
local conc_lease_ttl = 0
local conc_key_ttl = 0
local conc_key = nil

if has_conc == 1 then
    conc_max = tonumber(ARGV[arg_idx])
    conc_lease_ttl = tonumber(ARGV[arg_idx + 1])
    conc_key_ttl = tonumber(ARGV[arg_idx + 2])
    conc_key = KEYS[n + 1]

    -- Maintenance: reap expired leases
    redis.call('ZREMRANGEBYSCORE', conc_key, '-inf', now_ms)
    local active_count = redis.call('ZCARD', conc_key)

    if active_count < conc_max then
        conc_allowed = 1
    else
        conc_allowed = 0
        all_allowed = false
        local earliest = redis.call('ZRANGE', conc_key, 0, 0, 'WITHSCORES')
        if #earliest >= 2 then
            conc_retry = math.max(1, tonumber(earliest[2]) - now_ms)
        else
            conc_retry = 1
        end
    end
end

-- =========================================================================
-- Phase 2: Commit or Rollback
-- =========================================================================
if all_allowed then
    -- Persist all rate rule state
    for i = 1, n do
        if rule_eval[i].write_fn then
            rule_eval[i].write_fn()
        end
    end
    -- Acquire concurrency permit
    if has_conc == 1 then
        redis.call('ZADD', conc_key, now_ms + conc_lease_ttl, event_id)
        redis.call('PEXPIRE', conc_key, conc_key_ttl)
    end
end

-- =========================================================================
-- Select Binding Rule and Output [allowed, binding_rule, reason, remaining, limit, reset_at_ms, retry_after_ms, now_ms]
-- =========================================================================
if all_allowed then
    local best_idx = 1
    local min_ratio = 1.1
    if n > 0 then
        for i = 1, n do
            local ratio = rule_eval[i].remaining / math.max(1, rule_eval[i].limit)
            if ratio < min_ratio then
                min_ratio = ratio
                best_idx = i
            end
        end
        local r = rule_eval[best_idx]
        return {1, best_idx, 0, r.remaining, r.limit, r.reset_at_ms, 0, now_ms}
    else
        -- Pure concurrency policy without rate rules
        return {1, 0, 0, 0, conc_max, now_ms + conc_lease_ttl, 0, now_ms}
    end
else
    -- Deny
    if failing_rule_idx > 0 then
        -- Rate rule denied
        local r = rule_eval[failing_rule_idx]
        return {0, failing_rule_idx, 1, r.remaining, r.limit, r.reset_at_ms, r.retry_after_ms, now_ms}
    else
        -- Concurrency denied
        return {0, 0, 2, 0, conc_max, now_ms + conc_retry, conc_retry, now_ms}
    end
end
