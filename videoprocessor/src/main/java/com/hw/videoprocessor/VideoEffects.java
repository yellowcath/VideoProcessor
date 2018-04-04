package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaMetadataRetriever;
import com.hw.videoprocessor.util.CL;

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
        long s = System.currentTimeMillis();
        File cacheDir = new File(context.getCacheDir(), "kichiku_" + System.currentTimeMillis());
        cacheDir.mkdir();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(inputVideo);
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        retriever.release();
        CL.w("加速视频+");
        File speedVideo = new File(cacheDir, "speed_" + speed + ".tmp");
        VideoProcessor.processVideo(context, inputVideo, speedVideo.getAbsolutePath(), null, null, null, null,
                speed, null, null);
        CL.w("加速视频-");
        int bitrate = VideoUtil.getBitrateForAllKeyFrameVideo(inputVideo);
        List<File> fileList;
        try {
            CL.w("切割视频+");
            fileList = VideoUtil.splitVideo(context, speedVideo.getAbsolutePath(), cacheDir.getAbsolutePath(), splitTimeMs, 500, bitrate, 0);
        } catch (MediaCodec.CodecException e) {
            CL.e(e);
            /** Nexus5上-1代表全关键帧*/
            fileList = VideoUtil.splitVideo(context, speedVideo.getAbsolutePath(), cacheDir.getAbsolutePath(), splitTimeMs, 500, bitrate, -1);
        }
        CL.w("切割视频-");
        File cacheCombineFile = new File(cacheDir, "combine_" + System.currentTimeMillis() + ".tmp");
        CL.w("合并视频+");
        VideoUtil.combineVideos(fileList, cacheCombineFile.getAbsolutePath());
        CL.w("合并视频-");
        CL.w("视频转码+");
        VideoProcessor.processVideo(context, cacheCombineFile.getAbsolutePath(), outputVideo, null, null, null, null, null,
                oriBitrate, VideoProcessor.DEFAULT_I_FRAME_INTERVAL);
        cacheCombineFile.delete();
        CL.w("视频转码-");
        long e = System.currentTimeMillis();
        CL.e("鬼畜已完成,耗时:"+(e-s)/1000f+"s");
    }
}
