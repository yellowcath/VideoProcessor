package com.hw.videoprocessor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.support.annotation.Nullable;
import android.view.Surface;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.InputSurface;
import com.hw.videoprocessor.util.OutputSurface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.VideoProcessor.TIMEOUT_USEC;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class VideoDecodeThread extends Thread {
    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private Integer mStartTimeMs;
    private Integer mEndTimeMs;
    private Float mSpeed;
    private AtomicBoolean mDecodeDone;
    private Exception mException;
    private int mVideoIndex;
    private IVideoEncodeThread mVideoEncodeThread;
    private InputSurface mInputSurface;
    private OutputSurface mOutputSurface;
    private Integer mFrameRate;

    public VideoDecodeThread(IVideoEncodeThread videoEncodeThread, MediaExtractor extractor,
                             @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                             @Nullable Integer frameRate, @Nullable Float speed,
                             int videoIndex, AtomicBoolean decodeDone

    ) {
        super("VideoProcessDecodeThread");
        mExtractor = extractor;
        mStartTimeMs = startTimeMs;
        mEndTimeMs = endTimeMs;
        mSpeed = speed;
        mVideoIndex = videoIndex;
        mDecodeDone = decodeDone;
        mVideoEncodeThread = videoEncodeThread;
        mFrameRate = frameRate;
    }

    @Override
    public void run() {
        super.run();
        try {
            doDecode();
        } catch (Exception e) {
            mException = e;
            CL.e(e);
        } finally {
            mInputSurface.release();
            mOutputSurface.release();
            mDecoder.stop();
            mDecoder.release();
        }
    }

    private void doDecode() throws IOException {
        CountDownLatch eglContextLatch = mVideoEncodeThread.getEglContextLatch();
        try {
            eglContextLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Surface encodeSurface = mVideoEncodeThread.getSurface();
        mInputSurface = new InputSurface(encodeSurface);
        mInputSurface.makeCurrent();

        MediaFormat inputFormat = mExtractor.getTrackFormat(mVideoIndex);

        //初始化解码器
        mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        mOutputSurface = new OutputSurface();
        mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
        mDecoder.start();

        int frameIntervalForDrop = 0;
        int dropCount = 0;
        int frameIndex = 1;
        if (mFrameRate != null && VideoProcessor.DROP_FRAMES) {
            int sourceFrameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 0;
            if (sourceFrameRate != 0 && sourceFrameRate > mFrameRate) {
                frameIntervalForDrop = mFrameRate / (sourceFrameRate - mFrameRate);
                frameIntervalForDrop = frameIntervalForDrop == 0 ? 1 : frameIntervalForDrop;
                dropCount = (sourceFrameRate - mFrameRate) / mFrameRate;
                dropCount = dropCount == 0 ? 1 : dropCount;
                CL.w("帧率过高，需要丢帧:" + sourceFrameRate + "->" + mFrameRate + " frameIntervalForDrop:" + frameIntervalForDrop + " dropCount:" + dropCount);
            }
        }
        //开始解码
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean decoderDone = false;
        boolean inputDone = false;
        long videoStartTimeUs = -1;
        while (!decoderDone) {
            //还有帧数据，输入解码器
            if (!inputDone) {
                boolean eof = false;
                int index = mExtractor.getSampleTrackIndex();
                if (index == mVideoIndex) {
                    int inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                        int chunkSize = mExtractor.readSampleData(inputBuf, 0);
                        if (chunkSize < 0) {
                            mDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderDone = true;
                        } else {
                            long sampleTime = mExtractor.getSampleTime();
                            mDecoder.queueInputBuffer(inputBufIndex, 0, chunkSize, sampleTime, 0);
                            mExtractor.advance();
                        }
                    }
                } else if (index == -1) {
                    eof = true;
                }

                if (eof) {
                    //解码输入结束
                    CL.i("inputDone");
                    int inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        mDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    }
                }
            }
            boolean decoderOutputAvailable = !decoderDone;
            if (decoderDone) {
                CL.i("decoderOutputAvailable:" + decoderOutputAvailable);
            }
            while (decoderOutputAvailable) {
                int outputBufferIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                CL.i("outputBufferIndex = " + outputBufferIndex);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mDecoder.getOutputFormat();
                    CL.i("decode newFormat = " + newFormat);
                } else if (outputBufferIndex < 0) {
                    //ignore
                    CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                } else {
                    boolean doRender = true;
                    //解码数据可用
                    if (mEndTimeMs != null && info.presentationTimeUs >= mEndTimeMs * 1000) {
                        inputDone = true;
                        decoderDone = true;
                        doRender = false;
                        info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    }
                    if (mStartTimeMs != null && info.presentationTimeUs < mStartTimeMs * 1000) {
                        doRender = false;
                        CL.e("drop frame startTime = " + mStartTimeMs + " present time = " + info.presentationTimeUs / 1000);
                    }
                    if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                        decoderDone = true;
                        mDecoder.releaseOutputBuffer(outputBufferIndex, false);
                        CL.i("decoderDone");
                        break;
                    }
                    //检查是否需要丢帧
                    if (frameIntervalForDrop > 0) {
                        int remainder = frameIndex % (frameIntervalForDrop + dropCount);
                        if (remainder > frameIntervalForDrop || remainder == 0) {
                            CL.w("帧率过高，丢帧:" + frameIndex);
                            doRender = false;
                        }
                    }
                    frameIndex++;
                    mDecoder.releaseOutputBuffer(outputBufferIndex, doRender);
                    if (doRender) {
                        boolean errorWait = false;
                        try {
                            mOutputSurface.awaitNewImage();
                        } catch (Exception e) {
                            errorWait = true;
                            CL.e(e.getMessage());
                        }
                        if (!errorWait) {
                            if (videoStartTimeUs == -1) {
                                videoStartTimeUs = info.presentationTimeUs;
                                CL.i("videoStartTime:" + videoStartTimeUs / 1000);
                            }
                            mOutputSurface.drawImage(false);
                            long presentationTimeNs = (info.presentationTimeUs - videoStartTimeUs) * 1000;
                            if (mSpeed != null) {
                                presentationTimeNs /= mSpeed;
                            }
                            CL.i("drawImage,setPresentationTimeMs:" + presentationTimeNs / 1000 / 1000);
                            mInputSurface.setPresentationTime(presentationTimeNs);
                            mInputSurface.swapBuffers();
                            break;
                        }
                    }
                }
            }
        }
        mDecodeDone.set(true);
    }

    public Exception getException() {
        return mException;
    }
}