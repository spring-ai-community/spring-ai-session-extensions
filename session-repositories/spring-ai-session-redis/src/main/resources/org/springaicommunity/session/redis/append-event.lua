#!lua
-- Appends one event, unless its id is already in the log.
-- KEYS[1] the session hash
-- ARGV[1] event id, ARGV[2] encoded event, ARGV[3] '1' if the event is a compaction boundary
-- Returns 1 when appended, 0 for a retried append, -1 when the session does not exist.
-- Every check runs before the first write, because a script is not rolled back.
if redis.call('EXISTS', KEYS[1]) == 0 then
	return -1
end
if redis.call('HEXISTS', KEYS[1], 'i:' .. ARGV[1]) == 1 then
	return 0
end
local seq = tonumber(redis.call('HGET', KEYS[1], 'next'))
redis.call('HSET', KEYS[1], 'e:' .. seq, ARGV[2], 'i:' .. ARGV[1], seq, 'next', seq + 1)
redis.call('HINCRBY', KEYS[1], 'version', 1)
if ARGV[3] == '1' then
	redis.call('HSET', KEYS[1], 'boundary', seq)
end
return 1
