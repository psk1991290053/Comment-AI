-- 参数：ARGV[1]=voucherId  ARGV[2]=userId  ARGV[3]=orderId
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1.判断库存是否充足
--   注意：key 不存在时 redis.call('get') 返回 false，必须先排除，否则 tonumber(false) 得到 nil，比较会报错
local stock = redis.call('get', stockKey)
if (stock == false or tonumber(stock) <= 0) then
    return 1
end

-- 2.判断该用户是否已经下过单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3.扣减库存
redis.call('incrby', stockKey, -1)
-- 4.记录下单用户
redis.call('sadd', orderKey, userId)
-- 5.把订单消息投递到 Stream，交给消费者组异步落库
--   XADD stream.orders * userId xx voucherId xx id xx
redis.call('xadd', 'stream.orders', '*',
        'userId', userId,
        'voucherId', voucherId,
        'id', orderId)
-- 6.返回0表示有购买资格
return 0
