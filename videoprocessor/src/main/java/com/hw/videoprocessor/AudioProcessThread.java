package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.support.annotation.Nullable;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.VideoProgressAve;
import com.hw.videoprocessor.util.VideoProgressListener;

import java.util.concurrent.CountDownLatch;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class AudioProcessThread extends Thread implements VideoProgressListener {

    private String mVidepPath;
    private Integer mStartTimeMs;
    private Integer mEndTimeMs;
    private Float mSpeed;
    private Context mContext;
    private Exception mException;
    private MediaMuxer mMuxer;
    private int mMuxerAudioTrackIndex;
    private MediaExtractor mExtractor;
    private CountDownLatch mMuxerStartLatch;
    private VideoProgressAve mProgressAve;

    public AudioProcessThread(Context context, String videoPath, MediaMuxer muxer,
                              @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                              @Nullable Float speed, int muxerAudioTrackIndex,
                              CountDownLatch muxerStartLatch

    ) {
        super("VideoProcessDecodeThread");
        mVidepPath = videoPath;
        mStartTimeMs = startTimeMs;
        mEndTimeMs = endTimeMs;
        mSpeed = speed;
        mMuxer = muxer;
        mContext = context;
        mMuxerAudioTrackIndex = muxerAudioTrackIndex;
        mExtractor = new MediaExtractor();
        mMuxerStartLatch = muxerStartLatch;
    }

    @Override
    public void run() {
        super.run();
        try {
            doProcessAudio();
        } catch (Exception e) {
            mException = e;
            CL.e(e);
        } finally {
            mExtractor.release();
        }
    }

    private void doProcessAudio() throws Exception {
        mExtractor.setDataSource(mVidepPath);
        int audioTrackIndex = VideoUtil.selectTrack(mExtractor, true);
        if (audioTrackIndex >= 0) {
            //处理音频
            mExtractor.selectTrack(audioTrackIndex);
            //音频暂不支持变速
            Integer startTimeUs = mStartTimeMs == null ? null : mStartTimeMs * 1000;
            Integer endTimeUs = mEndTimeMs == null ? null : mEndTimeMs * 1000;
            mMuxerStartLatch.await();
            if (mSpeed != null) {
                AudioUtil.writeAudioTrackDecode(mContext, mExtractor, mMuxer, mMuxerAudioTrackIndex, startTimeUs, endTimeUs, mSpeed, this);
            } else {
                AudioUtil.writeAudioTrack(mExtractor, mMuxer, mMuxerAudioTrackIndex, startTimeUs, endTimeUs, this);
            }
        }
        if (mProgressAve != null) {
            mProgressAve.setAudioProgress(1);
        }
    }

    public Exception getException() {
        return mException;
    }

    public void setProgressAve(VideoProgressAve progressAve) {
        mProgressAve = progressAve;
    }

    @Override
    public void onProgress(float progress) {
        if (mProgressAve != null) {
            mProgressAve.setAudioProgress(progress);
        }
    }
}