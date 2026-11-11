package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //锁在redis里的统一前缀
    public static final String prefix = "lock:";
    public static final String uuid = UUID.randomUUID().toString(true) + "-";

    @Override
    public Boolean tryLock(Long timeoutSec) {
        //尝试获取锁
        String threadId = Thread.currentThread().getId() + uuid;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(prefix + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(prefix + name),
                Thread.currentThread().getId() + uuid
                );
    }

    /*
     * @Override
    public void unLock() {
        //获取redis中锁的id
        String redisId = stringRedisTemplate.opsForValue().get(prefix + name);
        //获取当前线程自身的id
        String threadId = Thread.currentThread().getId() + uuid;
        //如果一致，则代表是自己的锁，可以释放
        if(redisId.equals(threadId)){
            //释放锁
            stringRedisTemplate.delete(prefix + name);
        }
    }
    */


}
