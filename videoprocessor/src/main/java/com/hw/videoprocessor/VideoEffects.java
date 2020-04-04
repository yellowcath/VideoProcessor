package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaMetadataRetriever;
import com.hw.videoprocessor.util.CL;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Created by huangwei on 2018/3/8 0008.
 */

public class VideoEffects {

    /**
     * 鬼畜效果，先按speed倍率对视频进行加速，然后按splitTimeMs分割视频，并对每一个片段做正放+倒放
     */
    public static void doKichiku(Context context, VideoProcessor.MediaSource inputVideo, String outputVideo, @Nullable Integer outBitrate, float speed, int splitTimeMs) throws Exception {
        long s = System.currentTimeMillis();
        File cacheDir = new File(context.getCacheDir(), "kichiku_" + System.currentTimeMillis());
        cacheDir.mkdir();
        if (outBitrate == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            inputVideo.setDataSource(retriever);
            outBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            retriever.release();
        }
        int bitrate = VideoUtil.getBitrateForAllKeyFrameVideo(inputVideo);
        List<File> fileList;
        try {
            CL.w("切割视频+");
            fileList = VideoUtil.splitVideo(context, inputVideo, cacheDir.getAbsolutePath(), splitTimeMs, 500, bitrate, speed, 0);
        } catch (MediaCodec.CodecException e) {
            CL.e(e);
            /** Nexus5上-1代表全关键帧*/
            fileList = VideoUtil.splitVideo(context, inputVideo, cacheDir.getAbsolutePath(), splitTimeMs, 500, bitrate, speed, -1);
        }
        CL.w("切割视频-");
        CL.w("合并视频+");
        VideoUtil.combineVideos(fileList, outputVideo, outBitrate, VideoProcessor.DEFAULT_I_FRAME_INTERVAL);
        CL.w("合并视频-");
        long e = System.currentTimeMillis();
        CL.e("鬼畜已完成,耗时:" + (e - s) / 1000f + "s");
    }
}
