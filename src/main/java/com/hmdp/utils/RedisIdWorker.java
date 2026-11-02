//全局ID生成器
package com.hmdp.utils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //开始时间戳--2026-11-1-0-0-0
    private static final long BEGIN_TIMESTAMP = 1793491200;
    private static final long BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String prefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //2.生成自增字段
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("increment:" + prefix + ":" + date);
        //3.拼接返回
        return timestamp << BITS | count;
    }


}
