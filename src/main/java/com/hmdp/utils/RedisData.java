package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // 自己设置的逻辑过期时间
    // 这个时间是用于给程序判断这个key是否已经过期的，因为这些热带key会在活动前提前存到Redis中
    private LocalDateTime expireTime;
    // 这里的数据可以存储redis的数据（不用改实体代码，但复杂）
    // 比实体继承RedisData（获取expireTime这个属性）的方法要好（要修改代码，但简单）
    // 装饰器模式：通过将对象放入包含行为的特殊包装类中来为原始对象动态的添加新行为。
    // 这种模式是继承的一种替代方案，可以灵活的扩展对象的功能
    private Object data;
}
