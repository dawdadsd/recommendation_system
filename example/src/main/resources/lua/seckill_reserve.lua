-- KEYS[1] = seckill:stock:{skuId}:available
-- KEYS[2] = seckill:users:{activityId}:{skuId}
-- KEYS[3] = seckill:reservation:{reservationId}
-- KEYS[4] = seckill:release:zset
--
-- ARGV[1] = userId
-- ARGV[2] = reservationId
-- ARGV[3] = activityId
-- ARGV[4] = skuId
-- ARGV[5] = expireAtEpochSecond

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {0, 'ACTIVITY_CLOSED'}
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return {0, 'DUPLICATE_USER'}
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    return {0, 'SOLD_OUT'}
end

redis.call('DECR', KEYS[1])

redis.call('SADD', KEYS[2], ARGV[1])

redis.call('HSET', KEYS[3],
    'reservationId', ARGV[2],
    'activityId', ARGV[3],
    'skuId', ARGV[4],
    'userId', ARGV[1],
    'expireAt', ARGV[5],
    'status', 'RESERVED')

redis.call('ZADD', KEYS[4], ARGV[5], ARGV[2])

return {1, 'RESERVED'}
