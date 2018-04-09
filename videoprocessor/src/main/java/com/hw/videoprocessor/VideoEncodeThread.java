package com.hw.videoprocessor;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import com.hw.videoprocessor.util.CL;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.VideoProcessor.TIMEOUT_USEC;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class VideoEncodeThread extends Thread {

    private static final int WAIT_ENCODER_TIMEOUT = 40000;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private AtomicBoolean mDecodeDone;
    private CountDownLatch mMuxerStartLatch;
    private Exception mException;
    private VideoDecodeThread mDecodeThread;

    public VideoEncodeThread(VideoDecodeThread decodeThread, MediaMuxer muxer,
                             AtomicBoolean decodeDone, CountDownLatch muxerStartLatch) {
        super("VideoProcessEncodeThread");
        mDecodeThread = decodeThread;
        mMuxer = muxer;
        mDecodeDone = decodeDone;
        mMuxerStartLatch = muxerStartLatch;
    }

    @Override
    public void run() {
        super.run();
        try {
            doEncode();
        } catch (Exception e) {
            CL.e(e);
            mException = e;
        } finally {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
            }
        }
    }

    private void doEncode() {
        long waitStart = System.currentTimeMillis();
        while (mEncoder == null) {
            mEncoder = mDecodeThread.getEncoder();
            if (mEncoder != null) {
                CL.w("Encoder Got");
                break;
            }
//            CL.w("Wait Encoder......");
            SystemClock.sleep(20);
            if (System.currentTimeMillis() - waitStart > WAIT_ENCODER_TIMEOUT) {
                CL.w("Wait Encoder Timeout");
                mException = new RuntimeException("Wait Encoder Timeout!");
                return;
            }
        }
        Semaphore drawBufferSemaphore = mDecodeThread.getDrawBufferSemaphore();

        boolean signalEncodeEnd = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int encodeTryAgainCount = 0;
        int videoTrackIndex = -5;
        //开始编码
        //输出
        while (true) {
            if (mDecodeDone.get() && !signalEncodeEnd) {
                signalEncodeEnd = true;
                mEncoder.signalEndOfInputStream();
            }
            int outputBufferIndex = mEncoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
            CL.i("encode outputBufferIndex = " + outputBufferIndex);
            if (signalEncodeEnd && outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                encodeTryAgainCount++;
                if (encodeTryAgainCount > 10) {
                    //三星S8上出现signalEndOfInputStream之后一直tryAgain的问题
                    CL.e("INFO_TRY_AGAIN_LATER 10 times,force End!");
                    break;
                }
            } else {
                encodeTryAgainCount = 0;
            }
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mEncoder.getOutputFormat();
                if (videoTrackIndex == -5) {
                    videoTrackIndex = mMuxer.addTrack(newFormat);
                    mMuxer.start();
                    mMuxerStartLatch.countDown();
                }
                CL.i("encode newFormat = " + newFormat);
            } else if (outputBufferIndex < 0) {
                //ignore
                CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
            } else {
                //编码数据可用
                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                CL.i("writeSampleData,size:" + info.size + " time:" + info.presentationTimeUs / 1000);
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM && info.presentationTimeUs < 0) {
                    info.presentationTimeUs = 0;
                }
                mMuxer.writeSampleData(videoTrackIndex, outputBuffer, info);

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                drawBufferSemaphore.release();
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    CL.i("encoderDone");
                    break;
                }
            }
        }
    }

    public Exception getException() {
        return mException;
    }
}
