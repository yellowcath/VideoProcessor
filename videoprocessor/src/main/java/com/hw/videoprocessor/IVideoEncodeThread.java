package com.hw.videoprocessor;

import android.view.Surface;

import java.util.concurrent.CountDownLatch;

/**
 * Created by huangwei on 2018/4/10 0010.
 */

public interface IVideoEncodeThread {
    public Surface getSurface();
    public CountDownLatch getEglContextLatch();
}
