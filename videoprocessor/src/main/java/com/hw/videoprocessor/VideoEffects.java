package com.hw.videoprocessor;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Created by huangwei on 2018/3/8 0008.
 */

public class VideoEffects {

    /**
     * 鬼畜效果，先按speed倍率对视频进行加速，然后按splitTimeMs分割视频，并对每一个片段做正放+倒放
     */
    public static void doKichiku(Context context, String inputVideo, String outputVideo, float speed, int splitTimeMs) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "kichiku_" + System.currentTimeMillis());
        cacheDir.mkdir();
        File speedVideo = new File(cacheDir, "speed_" + speed + ".mp4");
        VideoProcessor.processVideo(context, inputVideo, speedVideo.getAbsolutePath(), null, null, null, null,
                speed, null, null);
        List<File> fileList = VideoUtil.splitVideo(context,speedVideo.getAbsolutePath(), cacheDir.getAbsolutePath(), splitTimeMs, 500,0);
        VideoUtil.combineVideos(fileList, outputVideo);
    }
}
