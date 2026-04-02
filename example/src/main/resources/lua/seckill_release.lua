-- 调用协议说明

-- KEYS[1] = seckill:stock:{skuId}:available
-- KEYS[2] = seckill:users:{activityId}:{skuId}
-- KEYS[3] = seckill:reservation:{reservationId}
-- KEYS[4] = seckill:release:zset
--
-- ARGV[1] = userId
-- ARGV[2] = reservationId

if redis.call('EXISTS', KEYS[3]) == 0 then
    return {0, "NOT_FOUND"}
end

local status = redis.call('HGET', KEYS[3], 'status')
if status == 'RELEASED' or status == 'PAID' then
    return {0, 'ALREADY_TERMINAL'}
end

redis.call('INCR', KEYS[1])

redis.call('SREM', KEYS[2], ARGV[1])

redis.call('HSET', KEYS[3], 'status', 'RELEASED')

redis.call('ZREM', KEYS[4], ARGV[2])

return {1, 'RELEASED'}
