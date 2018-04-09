package com.hw.videoprocessor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.support.annotation.Nullable;
import android.view.Surface;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.InputSurface;
import com.hw.videoprocessor.util.OutputSurface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.VideoProcessor.DEFAULT_FRAME_RATE;
import static com.hw.videoprocessor.VideoProcessor.MIME_TYPE;
import static com.hw.videoprocessor.VideoProcessor.TIMEOUT_USEC;

/**
 * Created by huangwei on 2018/4/8 0008.
 */

public class VideoDecodeThread extends Thread {
    static int MAX_BUFFER_SIZE = 3;

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private InputSurface mInputSurface;
    private Integer mStartTimeMs;
    private Integer mEndTimeMs;
    private Float mSpeed;
    private int mVideoIndex;
    private AtomicBoolean mDecodeDone;
    private Exception mException;
    private int mBitrate;
    private int mResultWidth;
    private int mResultHeight;
    private int mIFrameInterval;
    private volatile MediaCodec mEncoder;
    private Semaphore mDrawBufferSemaphore = new Semaphore(3);

    public VideoDecodeThread(MediaExtractor extractor,
                             int bitrate,int resultWidth,int resultHeight,
                             @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                             @Nullable Float speed,int iFrameInterval,
                             int videoIndex, AtomicBoolean decodeDone

    ) {
        super("VideoProcessDecodeThread");
        mExtractor = extractor;
        mStartTimeMs = startTimeMs;
        mEndTimeMs = endTimeMs;
        mSpeed = speed;
        mVideoIndex = videoIndex;
        mDecodeDone = decodeDone;
        mBitrate = bitrate;
        mResultHeight = resultHeight;
        mResultWidth = resultWidth;
        mIFrameInterval = iFrameInterval;
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
            mDecoder.stop();
            mDecoder.release();
        }
    }

    private void doDecode() throws IOException {
        MediaCodec encoder = null;
        MediaFormat inputFormat = mExtractor.getTrackFormat(mVideoIndex);
        //初始化编码器
        int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? inputFormat.getInteger(inputFormat.KEY_FRAME_RATE) : DEFAULT_FRAME_RATE;
        MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, mResultWidth, mResultHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mIFrameInterval);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encodeSurface = encoder.createInputSurface();

        mInputSurface = new InputSurface(encodeSurface);
        mInputSurface.makeCurrent();

        //初始化解码器
        mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        OutputSurface outputSurface = new OutputSurface();
        mDecoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
        mDecoder.start();
        encoder.start();
        mEncoder = encoder;

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
                    CL.i( "inputDone");
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
                    mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    if (doRender) {
                        boolean errorWait = false;
                        try {
                            outputSurface.awaitNewImage();
                        } catch (Exception e) {
                            errorWait = true;
                            CL.e(e.getMessage());
                        }
                        if (!errorWait) {
                            if (videoStartTimeUs == -1) {
                                videoStartTimeUs = info.presentationTimeUs;
                                CL.i("videoStartTime:" + videoStartTimeUs / 1000);
                            }
                            //
                            try {
                                CL.w("wait mDrawBufferSemaphore+");
                                mDrawBufferSemaphore.acquire(1);
                                CL.w("wait mDrawBufferSemaphore-");
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            outputSurface.drawImage(false);
                            long presentationTimeNs = (info.presentationTimeUs - videoStartTimeUs) * 1000;
                            if (mSpeed != null) {
                                presentationTimeNs /= mSpeed;
                            }
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

    public Semaphore getDrawBufferSemaphore() {
        return mDrawBufferSemaphore;
    }

    public Exception getException() {
        return mException;
    }

    public MediaCodec getEncoder() {
        return mEncoder;
    }
}