package com.hw.videoprocessor;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.Surface;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.InputSurface;
import com.hw.videoprocessor.util.OutputSurface;
import com.hw.videoprocessor.util.PcmToWavUtil;
import net.surina.soundtouch.SoundTouch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Created by huangwei on 2018/2/2.
 */
@TargetApi(21)
public class VideoProcessor {
    final static String TAG = "VideoProcessor";
    final static String MIME_TYPE = "video/avc";

    public final static int DEFAULT_FRAME_RATE = 25;
    /**
     * 只有关键帧距为0的才能方便做逆序
     */
    public final static int DEFAULT_I_FRAME_INTERVAL = 1;

    public static void scaleVideo(Context context, String input, String output,
                                  int outWidth, int outHeight) throws IOException {
        processVideo(context, input, output, outWidth, outHeight, null, null, null, null, null);
    }

    public static void cutVideo(Context context, String input, String output, int startTimeMs, int endTimeMs) throws IOException {
        processVideo(context, input, output, null, null, startTimeMs, endTimeMs, null, null, null);

    }

    public static void changeVideoSpeed(Context context, String input, String output, float speed) throws IOException {
        processVideo(context, input, output, null, null, null, null, speed, null, null);

    }


    /**
     * 对视频先检查，如果不是全关键帧，先处理成所有帧都是关键帧，再逆序
     */
    public static void revertVideo(Context context, String input, String output) throws IOException {
        File tempFile = new File(context.getCacheDir(), System.currentTimeMillis() + ".temp");
        File temp2File = new File(context.getCacheDir(), System.currentTimeMillis() + ".temp2");
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(input);
            int trackIndex = VideoUtil.selectTrack(extractor, false);
            extractor.selectTrack(trackIndex);
            int keyFrameCount = 0;
            int frameCount = 0;
            while (true) {
                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0) {
                    break;
                }
                int flags = extractor.getSampleFlags();
                if ((flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyFrameCount++;
                }
                frameCount++;
                extractor.advance();
            }
            extractor.release();
            if (frameCount == keyFrameCount) {
                revertVideoNoDecode(input, output);
            } else {
                int bitrateMultiple = (frameCount - keyFrameCount) / keyFrameCount;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(input);
                int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                int duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                processVideo(context, input, tempFile.getAbsolutePath(), null, null, null, null, null, oriBitrate * bitrateMultiple, 0);
                revertVideoNoDecode(tempFile.getAbsolutePath(), temp2File.getAbsolutePath());
                int oriIFrameInterval = (int) (keyFrameCount / (duration / 1000f));
                oriIFrameInterval = oriIFrameInterval == 0 ? 1 : oriIFrameInterval;
                processVideo(context, temp2File.getAbsolutePath(), output, null, null, null, null, null, oriBitrate, oriIFrameInterval);
            }
        } finally {
            tempFile.delete();
            temp2File.delete();
        }
    }

    /**
     * 支持裁剪缩放快慢放
     * 注意：不能在主线程进行
     */
    public static void processVideo(Context context, String input, String output,
                                    @Nullable Integer outWidth, @Nullable Integer outHeight,
                                    @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                                    @Nullable Float speed, @Nullable Integer bitrate,
                                    @Nullable Integer iFrameInterval) throws IOException {

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int originWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int originHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rotationValue = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        retriever.release();
        if (bitrate == null) {
            bitrate = oriBitrate;
        }
        if (iFrameInterval == null) {
            iFrameInterval = DEFAULT_I_FRAME_INTERVAL;
        }

        int resultWidth = outWidth == null ? originWidth : outWidth;
        int resultHeight = outHeight == null ? originHeight : outHeight;

        if (rotationValue == 90 || rotationValue == 270) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input);
        int videoIndex = VideoUtil.selectTrack(extractor, false);
        int audioIndex = VideoUtil.selectTrack(extractor, true);
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat audioTrackFormat = extractor.getTrackFormat(audioIndex);

        if (startTimeMs != null || endTimeMs != null || speed != null) {
            long durationUs = audioTrackFormat.getLong(MediaFormat.KEY_DURATION);
            if (startTimeMs != null && endTimeMs != null) {
                durationUs = (endTimeMs - startTimeMs) * 1000;
            }
            if (speed != null) {
                durationUs /= speed;
            }
            audioTrackFormat.setLong(MediaFormat.KEY_DURATION, durationUs);
        }
        int muxerAudioTrackIndex = mediaMuxer.addTrack(audioTrackFormat);
        extractor.selectTrack(videoIndex);
        if (startTimeMs != null) {
            extractor.seekTo(startTimeMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        } else {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        MediaCodec decoder = null;
        MediaCodec encoder = null;
        //初始化编码器
        MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = encoder.createInputSurface();
        InputSurface inputSurface = new InputSurface(surface);
        inputSurface.makeCurrent();

        //初始化解码器
        MediaFormat inputFormat = extractor.getTrackFormat(videoIndex);
        decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        OutputSurface outputSurface = new OutputSurface();
        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);

        decoder.start();
        encoder.start();

        final int TIMEOUT_USEC = 2500;
        long videoStartTimeUs = -1;

        try {
            //开始解码
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean encoderDone = false;
            boolean decoderDone = false;
            boolean inputDone = false;
            boolean signalEncodeEnd = false;
            int videoTrackIndex = -5;
            while (!decoderDone || !encoderDone) {
                //还有帧数据，输入解码器
                if (!inputDone) {
                    boolean eof = false;
                    int index = extractor.getSampleTrackIndex();
                    if (index == videoIndex) {
                        int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                        if (inputBufIndex >= 0) {
                            ByteBuffer inputBuf = decoder.getInputBuffer(inputBufIndex);
                            int chunkSize = extractor.readSampleData(inputBuf, 0);
                            if (chunkSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decoderDone = true;
                            } else {
                                long sampleTime = extractor.getSampleTime();
                                decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, sampleTime, 0);
                                extractor.advance();
                            }
                        }
                    } else if (index == -1) {
                        eof = true;
                    }

                    if (eof) {
                        //解码输入结束
                        CL.it(TAG, "inputDone");
                        int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                        if (inputBufIndex >= 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }
                boolean decoderOutputAvailable = !decoderDone;
                if (decoderDone) {
                    CL.it(TAG, "decoderOutputAvailable:" + decoderOutputAvailable);
                }
                while (decoderOutputAvailable) {
                    int outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    CL.it(TAG, "outputBufferIndex = " + outputBufferIndex);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        CL.it(TAG, "decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        boolean doRender = true;
                        //解码数据可用
                        if (endTimeMs != null && info.presentationTimeUs >= endTimeMs * 1000) {
                            inputDone = true;
                            decoderDone = true;
                            doRender = false;
                            info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        }
                        if (startTimeMs != null && info.presentationTimeUs < startTimeMs * 1000) {
                            doRender = false;
                            CL.et(TAG, "drop frame startTime = " + startTimeMs + " present time = " + info.presentationTimeUs / 1000);
                        }
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            decoderDone = true;
                            decoder.releaseOutputBuffer(outputBufferIndex, false);
                            CL.it(TAG, "decoderDone");
                            break;
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, true);
                        if (doRender) {
                            boolean errorWait = false;
                            try {
                                outputSurface.awaitNewImage();
                            } catch (Exception e) {
                                errorWait = true;
                                CL.et(TAG, e.getMessage());
                            }
                            if (!errorWait) {
                                if (videoStartTimeUs == -1) {
                                    videoStartTimeUs = info.presentationTimeUs;
                                    CL.it(TAG, "videoStartTime:" + videoStartTimeUs / 1000);
                                }
                                outputSurface.drawImage(false);
                                long presentationTimeNs = (info.presentationTimeUs - videoStartTimeUs) * 1000;
                                if (speed != null) {
                                    presentationTimeNs /= speed;
                                }
                                inputSurface.setPresentationTime(presentationTimeNs);
                                inputSurface.swapBuffers();
                                break;
                            }
                        }
                    }
                }

                //开始编码
                if (decoderDone && !signalEncodeEnd) {
                    signalEncodeEnd = true;
                    encoder.signalEndOfInputStream();
                }
                //输出
                boolean encoderOutputAvailable = !encoderDone;
                while (encoderOutputAvailable) {
                    int outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    CL.it(TAG, "encode outputBufferIndex = " + outputBufferIndex);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        if (videoTrackIndex == -5) {
                            videoTrackIndex = mediaMuxer.addTrack(newFormat);
                            mediaMuxer.start();
                        }
                        CL.it(TAG, "encode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        //编码数据可用
                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, info);
                        CL.it(TAG, "writeSampleData,size:" + info.size + " time:" + info.presentationTimeUs / 1000);

                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            encoderDone = true;
                            CL.it(TAG, "encoderDone");
                            break;
                        }
                    }
                }
            }
            encoder.stop();
            decoder.stop();

            //处理音频
            extractor.unselectTrack(videoIndex);
            //音频暂不支持变速
            Integer startTimeUs = startTimeMs == null ? null : (int) videoStartTimeUs;
            Integer endTimeUs = endTimeMs == null ? null : endTimeMs * 1000;
            if (speed != null) {
                writeAudioTrack(context, extractor, mediaMuxer, muxerAudioTrackIndex, startTimeUs, endTimeUs, speed);
            } else {
                VideoUtil.writeAudioTrack(extractor, mediaMuxer, muxerAudioTrackIndex, startTimeUs, endTimeUs);
            }
        } catch (Exception e) {
            CL.e(e);
        } finally {
            mediaMuxer.release();
            encoder.release();
            decoder.release();
            extractor.release();
        }
    }

    /**
     * 需要改变音频速率的情况下，需要先解码->改变速率->编码
     */
    private static void writeAudioTrack(Context context, MediaExtractor extractor, MediaMuxer mediaMuxer, int muxerAudioTrackIndex,
                                        Integer startTimeUs, Integer endTimeUs,
                                        @NonNull Float speed) throws Exception {
        int audioTrack = VideoUtil.selectTrack(extractor, true);
        extractor.selectTrack(audioTrack);
        if (startTimeUs == null) {
            startTimeUs = 0;
        }
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat oriAudioFormat = extractor.getTrackFormat(audioTrack);
        int maxBufferSize = VideoUtil.getAudioMaxBufferSize(oriAudioFormat);

        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        //调整音频速率需要重解码音频帧
        MediaCodec decoder = MediaCodec.createDecoderByType(oriAudioFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(oriAudioFormat, null, null, 0);
        decoder.start();


        boolean decodeDone = false;
        boolean encodeDone = false;
        boolean decodeInputDone = false;
        final int TIMEOUT_US = 2500;
        File pcmFile = new File(context.getCacheDir(), System.currentTimeMillis() + ".pcm");
        FileChannel writeChannel = new FileOutputStream(pcmFile).getChannel();
        try {
            while (!decodeDone) {
                if (!decodeInputDone) {
                    boolean eof = false;
                    int decodeInputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (decodeInputIndex >= 0) {
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs == -1) {
                            eof = true;
                        } else if (sampleTimeUs < startTimeUs) {
                            extractor.advance();
                            continue;
                        } else if (endTimeUs != null && sampleTimeUs > endTimeUs) {
                            eof = true;
                        }

                        if (eof) {
                            decodeInputDone = true;
                            decoder.queueInputBuffer(decodeInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            info.size = extractor.readSampleData(buffer, 0);
                            info.presentationTimeUs = sampleTimeUs;
                            info.flags = extractor.getSampleFlags();
                            ByteBuffer inputBuffer = decoder.getInputBuffer(decodeInputIndex);
                            inputBuffer.put(buffer);
                            CL.it(TAG, "audio decode queueInputBuffer " + info.presentationTimeUs / 1000);
                            decoder.queueInputBuffer(decodeInputIndex, 0, info.size, info.presentationTimeUs, info.flags);
                            extractor.advance();
                        }

                    }
                }

                while (!decodeDone) {
                    int outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        CL.it(TAG, "audio decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from audio decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            decodeDone = true;
                        } else {
                            ByteBuffer decodeOutputBuffer = decoder.getOutputBuffer(outputBufferIndex);
                            CL.it(TAG, "audio decode saveFrame " + info.presentationTimeUs / 1000);
                            writeChannel.write(decodeOutputBuffer);
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
        } finally {
            writeChannel.close();
            extractor.release();
            decoder.release();
        }

        int sampleRate = oriAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int oriChannelCount = oriAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        File wavFile = new File(context.getCacheDir(), pcmFile.getName() + ".wav");

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (oriChannelCount == 2) {
            channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }
        new PcmToWavUtil(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(pcmFile.getAbsolutePath(), wavFile.getAbsolutePath());
        //开始处理pcm
        CL.i(TAG, "start process pcm speed");
        File outFile = new File(context.getCacheDir(), pcmFile.getName() + ".outpcm");
        SoundTouch st = new SoundTouch();
        st.setTempo(speed);

        int res = st.processFile(wavFile.getAbsolutePath(), outFile.getAbsolutePath());
        if (res < 0) {
            pcmFile.delete();
            wavFile.delete();
            outFile.delete();
            return;
        }
        //重新将速率变化过后的pcm写入
        MediaExtractor pcmExtrator = new MediaExtractor();
        pcmExtrator.setDataSource(outFile.getAbsolutePath());
        audioTrack = VideoUtil.selectTrack(pcmExtrator, true);
        pcmExtrator.selectTrack(audioTrack);
        MediaFormat pcmTrackFormat = pcmExtrator.getTrackFormat(audioTrack);
        maxBufferSize = VideoUtil.getAudioMaxBufferSize(pcmTrackFormat);
        buffer = ByteBuffer.allocateDirect(maxBufferSize);

        int bitrate = oriAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
        int channelCount = oriAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);//参数对应-> mime type、采样率、声道数
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);//比特率
        encodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxBufferSize);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        boolean encodeInputDone = false;
        long lastAudioFrameTimeUs = -1;
        final int AAC_FRAME_TIME_US = 1024 * 1000 * 1000 / sampleRate;
        boolean detectTimeError = false;
        try {
            while (!encodeDone) {
                int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (!encodeInputDone && inputBufferIndex >= 0) {
                    long sampleTime = pcmExtrator.getSampleTime();
                    if (sampleTime < 0) {
                        encodeInputDone = true;
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        int flags = pcmExtrator.getSampleFlags();
                        buffer.clear();
                        int size = pcmExtrator.readSampleData(buffer, 0);
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(buffer);
                        inputBuffer.position(0);
                        CL.it(TAG, "audio queuePcmBuffer " + sampleTime / 1000 + " size:" + size);
                        encoder.queueInputBuffer(inputBufferIndex, 0, size, sampleTime, flags);
                        pcmExtrator.advance();
                    }
                }

                while (true) {
                    int outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        CL.it(TAG, "audio decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from audio decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            encodeDone = true;
                            break;
                        }
                        ByteBuffer encodeOutputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        CL.it(TAG, "audio writeSampleData " + info.presentationTimeUs + " size:" + info.size + " flags:" + info.flags);
                        if (!detectTimeError && lastAudioFrameTimeUs != -1 && info.presentationTimeUs < lastAudioFrameTimeUs + AAC_FRAME_TIME_US) {
                            //某些情况下帧时间会出错，目前未找到原因（系统相机录得双声道视频正常，我录的单声道视频不正常）
                            CL.et(TAG, "audio 时间戳错误，lastAudioFrameTimeUs:" + lastAudioFrameTimeUs + " " +
                                    "info.presentationTimeUs:" + info.presentationTimeUs);
                            detectTimeError = true;
                        }
                        if (detectTimeError) {
                            info.presentationTimeUs = lastAudioFrameTimeUs + AAC_FRAME_TIME_US;
                            CL.et(TAG, "audio 时间戳错误，使用修正的时间戳:" + info.presentationTimeUs);
                        }
                        lastAudioFrameTimeUs = info.presentationTimeUs;
                        mediaMuxer.writeSampleData(muxerAudioTrackIndex, encodeOutputBuffer, info);

                        encodeOutputBuffer.clear();
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
        } finally {
            pcmFile.delete();
            wavFile.delete();
            outFile.delete();
            pcmExtrator.release();
            encoder.release();
            mediaMuxer.release();
        }

    }

    /**
     * 直接对视频进行逆序,用于所有帧都是关键帧的情况
     */
    public static void revertVideoNoDecode(String input, String output) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int durationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input);

        int videoTrackIndex = VideoUtil.selectTrack(extractor, false);
        int audioTrackIndex = VideoUtil.selectTrack(extractor, true);

        final int MIN_FRAME_INTERVAL = 10 * 1000;
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        extractor.selectTrack(videoTrackIndex);
        MediaFormat videoTrackFormat = extractor.getTrackFormat(videoTrackIndex);
        int videoMuxerTrackIndex = mediaMuxer.addTrack(videoTrackFormat);

        MediaFormat audioTrackFormat = extractor.getTrackFormat(audioTrackIndex);
        int audioMuxerTrackIndex = mediaMuxer.addTrack(audioTrackFormat);
        mediaMuxer.start();
        int maxBufferSize = videoTrackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);

        extractor.seekTo(durationMs * 1000 + MIN_FRAME_INTERVAL, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long lastFrameTimeUs = -1;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            //写视频帧
            while (true) {
                long sampleTime = extractor.getSampleTime();
                if (lastFrameTimeUs == -1) {
                    lastFrameTimeUs = sampleTime;
                }
                info.presentationTimeUs = lastFrameTimeUs - sampleTime;
                info.size = extractor.readSampleData(buffer, 0);
                info.flags = extractor.getSampleFlags();

                if (info.size < 0) {
                    break;
                }
                mediaMuxer.writeSampleData(videoMuxerTrackIndex, buffer, info);
                long seekTime = sampleTime - MIN_FRAME_INTERVAL;
                if (seekTime <= 0) {
                    break;
                }
                extractor.seekTo(seekTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            //写音频帧
            extractor.unselectTrack(videoTrackIndex);
            extractor.selectTrack(audioTrackIndex);
            extractor.seekTo(durationMs * 1000 + MIN_FRAME_INTERVAL, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            lastFrameTimeUs = -1;
            while (true) {
                long sampleTime = extractor.getSampleTime();
                if (lastFrameTimeUs == -1) {
                    lastFrameTimeUs = sampleTime;
                }
                info.presentationTimeUs = lastFrameTimeUs - sampleTime;
                info.size = extractor.readSampleData(buffer, 0);
                info.flags = extractor.getSampleFlags();
                if (info.size < 0) {
                    break;
                }
                mediaMuxer.writeSampleData(audioMuxerTrackIndex, buffer, info);
                long seekTime = sampleTime - MIN_FRAME_INTERVAL;
                if (seekTime <= 0) {
                    break;
                }
                extractor.seekTo(seekTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
        } finally {
            extractor.release();
            mediaMuxer.release();
        }
    }

    /**
     * 不需要改变音频速率的情况下，直接读写就可
     * 只支持16bit音频
     *
     * @param videoVolumn 0静音，50表示原音
     * @param aacVolumn   0静音，50表示原音
     */
    public static void mixAudioTrack(Context context, String videoInput, String aacInput, String output,
                                     Integer startTimeMs, Integer endTimeMs,
                                     @IntRange(from = 0, to = 100) int videoVolumn,
                                     @IntRange(from = 0, to = 100) int aacVolumn) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "pcm");
        cacheDir.mkdir();

        File videoPcmFile = new File(cacheDir, "video_" + System.currentTimeMillis() + ".pcm");
        File aacPcmFile = new File(cacheDir, "aac_" + System.currentTimeMillis() + ".pcm");

        Integer startTimeUs = startTimeMs == null ? 0 : startTimeMs * 1000;
        Integer endTimeUs = endTimeMs == null ? null : endTimeMs * 1000;
        int duration = 0;
        if (endTimeUs == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoInput);
            duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } else {
            duration = endTimeUs - startTimeUs;
        }
        //音频转化为PCM
        decodeToPCM(videoInput, videoPcmFile.getAbsolutePath(), startTimeUs, endTimeUs);
        File tempAacFile = new File(cacheDir, "aactemp_" + System.currentTimeMillis() + ".pcm");
        decodeToPCM(aacInput, tempAacFile.getAbsolutePath(), 0, duration * 1000);
        boolean isVideoStereo = AudioUtil.isStereo(videoInput);
        boolean isAacStereo = AudioUtil.isStereo(aacInput);

        if (isAacStereo && isVideoStereo) {
            aacPcmFile = tempAacFile;
        } else if (isAacStereo) {
            //立体声转为单声道
            AudioUtil.stereoToMono(tempAacFile.getAbsolutePath(), aacPcmFile.getAbsolutePath());
        } else if (isVideoStereo) {
            File tempVideoPacFile = new File(cacheDir, "videotemp_" + System.currentTimeMillis() + ".pcm");
            AudioUtil.copyFile(videoPcmFile.getAbsolutePath(), tempVideoPacFile.getAbsolutePath());
            AudioUtil.stereoToMono(tempVideoPacFile.getAbsolutePath(), videoPcmFile.getAbsolutePath());
            tempVideoPacFile.delete();
        } else {
            aacPcmFile = tempAacFile;
        }
        //混合并调整音量
        File adjustedPcm = new File(cacheDir, "adjusted_" + System.currentTimeMillis() + ".pcm");
        AudioUtil.mixPcm(videoPcmFile.getAbsolutePath(), aacPcmFile.getAbsolutePath(), adjustedPcm.getAbsolutePath()
                , videoVolumn, aacVolumn);

        MediaExtractor oriExtrator = new MediaExtractor();
        oriExtrator.setDataSource(videoInput);
        int oriAudioIndex = VideoUtil.selectTrack(oriExtrator, true);
        MediaFormat oriAudioFormat = oriExtrator.getTrackFormat(oriAudioIndex);
        int oriChannelCount = oriAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int sampleRate = oriAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        File wavFile = new File(context.getCacheDir(), adjustedPcm.getName() + ".wav");

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (oriChannelCount == 2) {
            channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }
        //PCM转WAV
        new PcmToWavUtil(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath(), wavFile.getAbsolutePath());
        tempAacFile.delete();
        //重新写入音频
        final int TIMEOUT_US = 2500;
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        //重新将速率变化过后的pcm写入
        int oriVideoIndex = VideoUtil.selectTrack(oriExtrator, false);
        MediaFormat oriVideoFormat = oriExtrator.getTrackFormat(oriVideoIndex);
        int muxerVideoIndex = mediaMuxer.addTrack(oriVideoFormat);
        int muxerAudioIndex = mediaMuxer.addTrack(oriAudioFormat);
        mediaMuxer.start();

        MediaExtractor pcmExtrator = new MediaExtractor();
        pcmExtrator.setDataSource(wavFile.getAbsolutePath());
        int audioTrack = VideoUtil.selectTrack(pcmExtrator, true);
        pcmExtrator.selectTrack(audioTrack);
        MediaFormat pcmTrackFormat = pcmExtrator.getTrackFormat(audioTrack);
        int maxBufferSize = VideoUtil.getAudioMaxBufferSize(pcmTrackFormat);
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int bitrate = oriAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
        int channelCount = oriAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);//参数对应-> mime type、采样率、声道数
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);//比特率
        encodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxBufferSize);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        boolean encodeInputDone = false;
        boolean encodeDone = false;
        long lastAudioFrameTimeUs = -1;
        final int AAC_FRAME_TIME_US = 1024 * 1000 * 1000 / sampleRate;
        boolean detectTimeError = false;
        try {
            while (!encodeDone) {
                int inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (!encodeInputDone && inputBufferIndex >= 0) {
                    long sampleTime = pcmExtrator.getSampleTime();
                    if (sampleTime < 0) {
                        encodeInputDone = true;
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        int flags = pcmExtrator.getSampleFlags();
                        buffer.clear();
                        int size = pcmExtrator.readSampleData(buffer, 0);
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(buffer);
                        inputBuffer.position(0);
                        CL.it(TAG, "audio queuePcmBuffer " + sampleTime / 1000 + " size:" + size);
                        encoder.queueInputBuffer(inputBufferIndex, 0, size, sampleTime, flags);
                        pcmExtrator.advance();
                    }
                }

                while (true) {
                    int outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        CL.it(TAG, "audio decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from audio decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            encodeDone = true;
                            break;
                        }
                        ByteBuffer encodeOutputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        CL.it(TAG, "audio writeSampleData " + info.presentationTimeUs + " size:" + info.size + " flags:" + info.flags);
                        if (!detectTimeError && lastAudioFrameTimeUs != -1 && info.presentationTimeUs < lastAudioFrameTimeUs + AAC_FRAME_TIME_US) {
                            //某些情况下帧时间会出错，目前未找到原因（系统相机录得双声道视频正常，我录的单声道视频不正常）
                            CL.et(TAG, "audio 时间戳错误，lastAudioFrameTimeUs:" + lastAudioFrameTimeUs + " " +
                                    "info.presentationTimeUs:" + info.presentationTimeUs);
                            detectTimeError = true;
                        }
                        if (detectTimeError) {
                            info.presentationTimeUs = lastAudioFrameTimeUs + AAC_FRAME_TIME_US;
                            CL.et(TAG, "audio 时间戳错误，使用修正的时间戳:" + info.presentationTimeUs);
                        }
                        lastAudioFrameTimeUs = info.presentationTimeUs;
                        mediaMuxer.writeSampleData(muxerAudioIndex, encodeOutputBuffer, info);

                        encodeOutputBuffer.clear();
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
            //重新将视频写入
            oriExtrator.selectTrack(oriVideoIndex);
            oriExtrator.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            maxBufferSize = oriExtrator.getTrackFormat(oriVideoIndex).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            buffer = ByteBuffer.allocateDirect(maxBufferSize);

            while (true) {
                long sampleTimeUs = oriExtrator.getSampleTime();
                if (sampleTimeUs == -1) {
                    break;
                }
                if (sampleTimeUs < startTimeUs) {
                    oriExtrator.advance();
                    continue;
                }
                if (endTimeUs != null && sampleTimeUs > endTimeUs) {
                    break;
                }
                info.presentationTimeUs = sampleTimeUs - startTimeUs;
                info.flags = oriExtrator.getSampleFlags();
                info.size = oriExtrator.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }
                //写入视频
                mediaMuxer.writeSampleData(muxerVideoIndex, buffer, info);
                oriExtrator.advance();
            }
        } finally {
            aacPcmFile.delete();
            videoPcmFile.delete();
            adjustedPcm.delete();
            wavFile.delete();

            pcmExtrator.release();
            oriExtrator.release();
            mediaMuxer.release();
            encoder.stop();
            encoder.release();
        }

    }

    /**
     * 需要改变音频速率的情况下，需要先解码->改变速率->编码
     */
    private static void decodeToPCM(String audioPath, String outPath, Integer startTimeUs, Integer endTimeUs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(audioPath);
        int audioTrack = VideoUtil.selectTrack(extractor, true);
        extractor.selectTrack(audioTrack);
        if (startTimeUs == null) {
            startTimeUs = 0;
        }
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat oriAudioFormat = extractor.getTrackFormat(audioTrack);
        int maxBufferSize;
        if (oriAudioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = oriAudioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            maxBufferSize = 100 * 1000;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        //调整音频速率需要重解码音频帧
        MediaCodec decoder = MediaCodec.createDecoderByType(oriAudioFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(oriAudioFormat, null, null, 0);
        decoder.start();

        boolean decodeDone = false;
        boolean decodeInputDone = false;
        final int TIMEOUT_US = 2500;
        File pcmFile = new File(outPath);
        FileChannel writeChannel = new FileOutputStream(pcmFile).getChannel();
        try {
            while (!decodeDone) {
                if (!decodeInputDone) {
                    boolean eof = false;
                    int decodeInputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (decodeInputIndex >= 0) {
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs == -1) {
                            eof = true;
                        } else if (sampleTimeUs < startTimeUs) {
                            extractor.advance();
                            continue;
                        } else if (endTimeUs != null && sampleTimeUs > endTimeUs) {
                            eof = true;
                        }

                        if (eof) {
                            decodeInputDone = true;
                            decoder.queueInputBuffer(decodeInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            info.size = extractor.readSampleData(buffer, 0);
                            info.presentationTimeUs = sampleTimeUs;
                            info.flags = extractor.getSampleFlags();
                            ByteBuffer inputBuffer = decoder.getInputBuffer(decodeInputIndex);
                            inputBuffer.put(buffer);
                            CL.it(TAG, "audio decode queueInputBuffer " + info.presentationTimeUs / 1000);
                            decoder.queueInputBuffer(decodeInputIndex, 0, info.size, info.presentationTimeUs, info.flags);
                            extractor.advance();
                        }

                    }
                }

                while (!decodeDone) {
                    int outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        CL.it(TAG, "audio decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.et(TAG, "unexpected result from audio decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            decodeDone = true;
                        } else {
                            ByteBuffer decodeOutputBuffer = decoder.getOutputBuffer(outputBufferIndex);
                            CL.it(TAG, "audio decode saveFrame " + info.presentationTimeUs / 1000);
                            writeChannel.write(decodeOutputBuffer);
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
        } finally {
            writeChannel.close();
            extractor.release();
            decoder.stop();
            decoder.release();
        }
    }
}
