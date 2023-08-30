package com.krrz.utils;

public interface ILock {
    boolean tryLock(long timeoutSec);

    void unlock();
}
