#!lua
-- Appends a batch of events if the version still matches, and bumps the version once.
-- KEYS[1] the session hash
-- ARGV[1] expected version, ARGV[2] position in the batch of its last compaction boundary, or -1,
-- then the events as pairs: id, encoded event
-- Returns 1 when appended, 0 when the version differs, -1 when the session does not exist,
-- -2 when an id is already in the log.
-- Every check runs before the first write, because a script is not rolled back.
if redis.call('EXISTS', KEYS[1]) == 0 then
	return -1
end
if tonumber(redis.call('HGET', KEYS[1], 'version')) ~= tonumber(ARGV[1]) then
	return 0
end
for i = 3, #ARGV, 2 do
	if redis.call('HEXISTS', KEYS[1], 'i:' .. ARGV[i]) == 1 then
		return -2
	end
end
local first = tonumber(redis.call('HGET', KEYS[1], 'next'))
local seq = first
for i = 3, #ARGV, 2 do
	redis.call('HSET', KEYS[1], 'e:' .. seq, ARGV[i + 1], 'i:' .. ARGV[i], seq)
	seq = seq + 1
end
redis.call('HSET', KEYS[1], 'next', seq)
redis.call('HINCRBY', KEYS[1], 'version', 1)
if tonumber(ARGV[2]) >= 0 then
	redis.call('HSET', KEYS[1], 'boundary', first + tonumber(ARGV[2]))
end
return 1
