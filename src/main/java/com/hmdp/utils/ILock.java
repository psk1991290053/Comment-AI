package com.hmdp.utils;

public interface ILock {

    //尝试获取锁
    public Boolean tryLock(Long timeoutSec);

    //解锁
    public void unLock();

}
