#!lua flags=allow-oom
-- allow-oom: deleting frees memory, so it must run even when Redis is out of memory.
-- Deletes the session if it expired before the cut-off, in one step,
-- so a session whose expiry was just extended is kept.
-- KEYS[1] the session hash
-- ARGV[1] the cut-off (epoch micros)
-- Returns the metadata JSON when deleted, '' when kept, nil when the session does not exist.
if redis.call('EXISTS', KEYS[1]) == 0 then
	return false
end
local expires = redis.call('HGET', KEYS[1], 'expires')
if expires and tonumber(expires) < tonumber(ARGV[1]) then
	local metadata = redis.call('HGET', KEYS[1], 'metadata')
	redis.call('UNLINK', KEYS[1])
	return metadata
end
return ''
