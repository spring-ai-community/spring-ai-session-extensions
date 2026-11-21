#!lua
-- Creates or updates the session, keeping its creation time and its log.
-- KEYS[1] the session hash
-- ARGV[1] created (epoch micros), ARGV[2] expires (epoch micros, or '' for none), ARGV[3] metadata JSON
-- Returns the previous metadata JSON, or nil for a new session.
local previous = redis.call('HGET', KEYS[1], 'metadata')
redis.call('HSET', KEYS[1], 'metadata', ARGV[3])
if ARGV[2] ~= '' then
	redis.call('HSET', KEYS[1], 'expires', ARGV[2])
else
	redis.call('HDEL', KEYS[1], 'expires')
end
redis.call('HSETNX', KEYS[1], 'created', ARGV[1])
redis.call('HSETNX', KEYS[1], 'version', 0)
redis.call('HSETNX', KEYS[1], 'next', 0)
return previous
