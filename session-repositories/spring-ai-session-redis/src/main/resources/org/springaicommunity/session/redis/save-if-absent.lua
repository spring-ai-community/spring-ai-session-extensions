#!lua
-- Creates the session unless its key exists.
-- KEYS[1] the session hash
-- ARGV[1] created (epoch micros), ARGV[2] expires (epoch micros, or '' for none), ARGV[3] metadata JSON
-- Returns 1 when created, 0 when the session already exists.
if redis.call('EXISTS', KEYS[1]) == 1 then
	return 0
end
redis.call('HSET', KEYS[1], 'created', ARGV[1], 'metadata', ARGV[3], 'version', 0, 'next', 0)
if ARGV[2] ~= '' then
	redis.call('HSET', KEYS[1], 'expires', ARGV[2])
end
return 1
