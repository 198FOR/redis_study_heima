package com.hmdp.utils;

/**
 * Created by sopt on 6/17/23 17:41.
 */


public interface ILock {

    boolean tryLock(long expireTime);

    void unlock();
}
