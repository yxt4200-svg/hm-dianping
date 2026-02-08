---- 线程标识
--local threadId = "UUID-31"
---- 锁的key
--local key = "lock:order:userId"
---- 获取锁中线程标识
--local id = redis.call('get', key)
---- 比较线程标识与锁的标识是否一致
--if (threadId == id) then
--    -- 一致则释放锁 del key
--    return redis.call('del', key)
--end
--return 0

-- 这里的KEYS[1]就是传入锁的key
-- 这里的ARGV[1]就是线程标识
-- 比较锁中的线程标识与线程标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致则释放锁
    return redis.call('del', KEYS[1])
end
return 0