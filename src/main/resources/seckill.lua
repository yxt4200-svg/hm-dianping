-- 1.参数列表
-- 1.1 优惠券id
-- 从ARGV里取是因为它不是一个key,是个id,通过id拼接得到key
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[1]

-- 2.数据库key
-- 2.1 库key
local stockKey = 'seckill:stock' .. voucherId
-- 2.2 订单id
local orderKey = 'seckill:stock' .. voucherId

-- 3. 脚本业务
-- 3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get',stockKey)) <= 0) then
    -- 3.2 库存不足返回1
    return 1
end

-- 3.2 判断用户是否下单 SISMEMBER orderKey userId
if (redis.call('sismember',orderKey,userId) == 1) then
    -- 3.3 存在，说明重复下单，返回2
    return 2;
end

-- 3.4 扣库存 INCRBY stockKey -1
redis.call('incrby',stockKey,-1)

-- 3.5 下单 sadd orderKey userId
redis.call('sadd',orderKey,userId)
return 0

