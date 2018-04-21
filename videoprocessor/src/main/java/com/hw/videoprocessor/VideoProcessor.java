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
import android.support.annotation.Nullable;
import android.util.Pair;
import com.hw.videoprocessor.util.AudioFadeUtil;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.PcmToWavUtil;
import com.hw.videoprocessor.util.VideoMultiStepProgress;
import com.hw.videoprocessor.util.VideoProgressAve;
import com.hw.videoprocessor.util.VideoProgressListener;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by huangwei on 2018/2/2.
 */
@TargetApi(21)
public class VideoProcessor {
    final static String TAG = "VideoProcessor";
    final static String MIME_TYPE = "video/avc";
    /**
     * 帧率超过制定帧率时是否丢帧
     */
    public static boolean DROP_FRAMES = true;
    public static int DEFAULT_FRAME_RATE = 20;
    /**
     * 只有关键帧距为0的才能方便做逆序
     */
    public final static int DEFAULT_I_FRAME_INTERVAL = 1;

    public final static int DEFAULT_AAC_BITRATE = 192 * 1000;
    /**
     * 控制音频合成时，如果输入的音频文件长度不够，是否重复填充
     */
    public static boolean AUDIO_MIX_REPEAT = true;

    final static int TIMEOUT_USEC = 2500;


    public static void scaleVideo(Context context, String input, String output,
                                  int outWidth, int outHeight) throws Exception {
        processor(context)
                .input(input)
                .output(output)
                .outWidth(outWidth)
                .outHeight(outHeight)
                .process();
    }

    public static void cutVideo(Context context, String input, String output, int startTimeMs, int endTimeMs) throws Exception {
        processor(context)
                .input(input)
                .output(output)
                .startTimeMs(startTimeMs)
                .endTimeMs(endTimeMs)
                .process();
    }

    public static void changeVideoSpeed(Context context, String input, String output, float speed) throws Exception {
        processor(context)
                .input(input)
                .output(output)
                .speed(speed)
                .process();
    }


    /**
     * 对视频先检查，如果不是全关键帧，先处理成所有帧都是关键帧，再逆序
     */
    public static void reverseVideo(Context context, String input, String output, @Nullable VideoProgressListener listener) throws Exception {
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
                int flags = extractor.getSampleFlags();
                if (flags > 0 && (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyFrameCount++;
                }
                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0) {
                    break;
                }
                frameCount++;
                extractor.advance();
            }
            extractor.release();
            if (frameCount == keyFrameCount) {
                reverseVideoNoDecode(input, output, listener);
            } else {
                VideoMultiStepProgress stepProgress = new VideoMultiStepProgress(new float[]{0.45f, 0.1f, 0.45f}, listener);
                stepProgress.setCurrentStep(0);
                int bitrateMultiple = (frameCount - keyFrameCount) / keyFrameCount;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(input);
                int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                int duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                try {
                    processVideo(context, input, tempFile.getAbsolutePath(), null, null, null, null, null, oriBitrate * bitrateMultiple, null, 0, stepProgress);
                } catch (MediaCodec.CodecException e) {
                    CL.e(e);
                    /** Nexus5上-1代表全关键帧*/
                    processVideo(context, input, tempFile.getAbsolutePath(), null, null, null, null, null, oriBitrate * bitrateMultiple, null, -1, stepProgress);
                }
                stepProgress.setCurrentStep(1);
                reverseVideoNoDecode(tempFile.getAbsolutePath(), temp2File.getAbsolutePath(), stepProgress);
                int oriIFrameInterval = (int) (keyFrameCount / (duration / 1000f));
                oriIFrameInterval = oriIFrameInterval == 0 ? 1 : oriIFrameInterval;
                stepProgress.setCurrentStep(2);
                processVideo(context, temp2File.getAbsolutePath(), output, null, null, null, null, null, oriBitrate, null, oriIFrameInterval, stepProgress);
            }
        } finally {
            tempFile.delete();
            temp2File.delete();
        }
    }

    /**
     * 支持裁剪缩放快慢放
     *
     * @param frameRate 只有{@link #DROP_FRAMES}为true时才会进行丢帧，确保输出视频帧率接近frameRate
     */
    public static void processVideo(Context context, String input, String output,
                                    @Nullable Integer outWidth, @Nullable Integer outHeight,
                                    @Nullable Integer startTimeMs, @Nullable Integer endTimeMs,
                                    @Nullable Float speed, @Nullable Integer bitrate,
                                    @Nullable Integer frameRate, @Nullable Integer iFrameInterval,
                                    @Nullable VideoProgressListener listener) throws Exception {

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int originWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int originHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rotationValue = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        int duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();
        if (bitrate == null) {
            bitrate = oriBitrate;
        }
        if (iFrameInterval == null) {
            iFrameInterval = DEFAULT_I_FRAME_INTERVAL;
        }

        int resultWidth = outWidth == null ? originWidth : outWidth;
        int resultHeight = outHeight == null ? originHeight : outHeight;
        resultWidth = resultWidth % 2 == 0 ? resultWidth : resultWidth + 1;
        resultHeight = resultHeight % 2 == 0 ? resultHeight : resultHeight + 1;

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
        int muxerAudioTrackIndex = 0;
        if (audioIndex >= 0) {
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
            muxerAudioTrackIndex = mediaMuxer.addTrack(audioTrackFormat);
        }
        extractor.selectTrack(videoIndex);
        if (startTimeMs != null) {
            extractor.seekTo(startTimeMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        } else {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        VideoProgressAve progressAve = new VideoProgressAve(listener);
        progressAve.setSpeed(speed);
        progressAve.setStartTimeMs(startTimeMs == null ? 0 : startTimeMs);
        progressAve.setEndTimeMs(endTimeMs == null ? duration : endTimeMs);
        AtomicBoolean decodeDone = new AtomicBoolean(false);
        CountDownLatch muxerStartLatch = new CountDownLatch(1);
        VideoEncodeThread encodeThread = new VideoEncodeThread(extractor, mediaMuxer, bitrate,
                resultWidth, resultHeight, iFrameInterval, frameRate == null ? DEFAULT_FRAME_RATE : frameRate, videoIndex,
                decodeDone, muxerStartLatch);
        int srcFrameRate = VideoUtil.getFrameRate(input);
        if (srcFrameRate <= 0) {
            srcFrameRate = (int) Math.ceil(VideoUtil.getAveFrameRate(input));
        }
        VideoDecodeThread decodeThread = new VideoDecodeThread(encodeThread, extractor, startTimeMs, endTimeMs, srcFrameRate,
                frameRate == null ? DEFAULT_FRAME_RATE : frameRate, speed, videoIndex, decodeDone);
        AudioProcessThread audioProcessThread = new AudioProcessThread(context, input, mediaMuxer, startTimeMs, endTimeMs,
                speed, muxerAudioTrackIndex, muxerStartLatch);
        encodeThread.setProgressAve(progressAve);
        audioProcessThread.setProgressAve(progressAve);
        decodeThread.start();
        encodeThread.start();
        audioProcessThread.start();
        try {
            long s = System.currentTimeMillis();
            decodeThread.join();
            encodeThread.join();
            long e1 = System.currentTimeMillis();
            audioProcessThread.join();
            long e2 = System.currentTimeMillis();
            CL.w(String.format("编解码:%dms,音频:%dms", e1 - s, e2 - s));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            mediaMuxer.release();
            extractor.release();
        } catch (Exception e2) {
            CL.e(e2);
        }
        if (encodeThread.getException() != null) {
            throw encodeThread.getException();
        } else if (decodeThread.getException() != null) {
            throw decodeThread.getException();
        } else if (audioProcessThread.getException() != null) {
            throw audioProcessThread.getException();
        }
    }

    public static void reverseVideoNoDecode(String input, String output) throws IOException {
        reverseVideoNoDecode(input, output, null);
    }

    /**
     * 直接对视频进行逆序,用于所有帧都是关键帧的情况
     */
    public static void reverseVideoNoDecode(String input, String output, @Nullable VideoProgressListener listener) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int durationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input);

        int videoTrackIndex = VideoUtil.selectTrack(extractor, false);
        int audioTrackIndex = VideoUtil.selectTrack(extractor, true);
        boolean audioExist = audioTrackIndex >= 0;

        final int MIN_FRAME_INTERVAL = 10 * 1000;
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        extractor.selectTrack(videoTrackIndex);
        MediaFormat videoTrackFormat = extractor.getTrackFormat(videoTrackIndex);
        long videoDurationUs = videoTrackFormat.getLong(MediaFormat.KEY_DURATION);
        long audioDurationUs = 0;
        int videoMuxerTrackIndex = mediaMuxer.addTrack(videoTrackFormat);
        int audioMuxerTrackIndex = 0;
        if (audioExist) {
            MediaFormat audioTrackFormat = extractor.getTrackFormat(audioTrackIndex);
            audioMuxerTrackIndex = mediaMuxer.addTrack(audioTrackFormat);
            audioDurationUs = audioTrackFormat.getLong(MediaFormat.KEY_DURATION);
        }
        mediaMuxer.start();
        int maxBufferSize = videoTrackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        VideoUtil.seekToLastFrame(extractor, videoTrackIndex, durationMs);
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
                if (listener != null) {
                    float videoProgress = info.presentationTimeUs / (float) videoDurationUs;
                    videoProgress = videoProgress > 1 ? 1 : videoProgress;
                    videoProgress *= 0.7f;
                    listener.onProgress(videoProgress);
                }
                long seekTime = sampleTime - MIN_FRAME_INTERVAL;
                if (seekTime <= 0) {
                    break;
                }
                extractor.seekTo(seekTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            //写音频帧
            if (audioExist) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                VideoUtil.seekToLastFrame(extractor, audioTrackIndex, durationMs);
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
                    if (listener != null) {
                        float audioProgress = info.presentationTimeUs / (float) audioDurationUs;
                        audioProgress = audioProgress > 1 ? 1 : audioProgress;
                        audioProgress = 0.7f + audioProgress * 0.3f;
                        listener.onProgress(audioProgress);
                    }
                    long seekTime = sampleTime - MIN_FRAME_INTERVAL;
                    if (seekTime <= 0) {
                        break;
                    }
                    extractor.seekTo(seekTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                }
            }
            if (listener != null) {
                listener.onProgress(1f);
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
     * @param videoVolume 0静音，50表示原音
     * @param aacVolume   0静音，50表示原音
     */
    public static void mixAudioTrack(Context context, final String videoInput, final String aacInput, final String output,
                                     Integer startTimeMs, Integer endTimeMs,
                                     @IntRange(from = 0, to = 100) int videoVolume,
                                     @IntRange(from = 0, to = 100) int aacVolume,
                                     float fadeInSec, float fadeOutSec) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "pcm");
        cacheDir.mkdir();

        final File videoPcmFile = new File(cacheDir, "video_" + System.currentTimeMillis() + ".pcm");
        File aacPcmFile = new File(cacheDir, "aac_" + System.currentTimeMillis() + ".pcm");

        final Integer startTimeUs = startTimeMs == null ? 0 : startTimeMs * 1000;
        final Integer endTimeUs = endTimeMs == null ? null : endTimeMs * 1000;
        final int videoDurationMs;
        if (endTimeUs == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoInput);
            videoDurationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } else {
            videoDurationMs = (endTimeUs - startTimeUs) / 1000;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(aacInput);
        final int aacDurationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();

        MediaExtractor oriExtrator = new MediaExtractor();
        oriExtrator.setDataSource(videoInput);
        int oriAudioIndex = VideoUtil.selectTrack(oriExtrator, true);
        MediaExtractor aacExtractor = new MediaExtractor();
        aacExtractor.setDataSource(aacInput);
        int aacAudioIndex = VideoUtil.selectTrack(aacExtractor, true);
        File wavFile;
        int sampleRate;
        File adjustedPcm;
        int channelCount;
        int audioBitrate;
        final int TIMEOUT_US = 2500;
        //重新将速率变化过后的pcm写入
        int oriVideoIndex = VideoUtil.selectTrack(oriExtrator, false);
        MediaFormat oriVideoFormat = oriExtrator.getTrackFormat(oriVideoIndex);
        int rotation = oriVideoFormat.containsKey(MediaFormat.KEY_ROTATION) ? oriVideoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mediaMuxer.setOrientationHint(rotation);
        int muxerVideoIndex = mediaMuxer.addTrack(oriVideoFormat);
        int muxerAudioIndex;
        if (oriAudioIndex >= 0) {
            long s1 = System.currentTimeMillis();
            final CountDownLatch latch = new CountDownLatch(2);
            //音频转化为PCM
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioUtil.decodeToPCM(videoInput, videoPcmFile.getAbsolutePath(), startTimeUs, endTimeUs);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
            final File finalAacPcmFile = aacPcmFile;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioUtil.decodeToPCM(aacInput, finalAacPcmFile.getAbsolutePath(), 0, aacDurationMs > videoDurationMs ? videoDurationMs * 1000 : aacDurationMs * 1000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            long s2 = System.currentTimeMillis();

            MediaFormat oriAudioFormat = oriExtrator.getTrackFormat(oriAudioIndex);
            audioBitrate = AudioUtil.getAudioBitrate(oriAudioFormat);
            muxerAudioIndex = mediaMuxer.addTrack(oriAudioFormat);

            //检查两段音频格式是否一致,不一致则统一转换为单声道+44100
            Pair<Integer, Integer> resultPair = AudioUtil.checkAndAdjustAudioFormat(videoPcmFile.getAbsolutePath(),
                    aacPcmFile.getAbsolutePath(),
                    oriExtrator.getTrackFormat(oriAudioIndex),
                    aacExtractor.getTrackFormat(aacAudioIndex)
            );
            channelCount = resultPair.first;
            sampleRate = resultPair.second;
            aacExtractor.release();
            long s3 = System.currentTimeMillis();

            //检查音频长度是否需要重复填充
            if (AUDIO_MIX_REPEAT) {
                aacPcmFile = AudioUtil.checkAndFillPcm(aacPcmFile, aacDurationMs, videoDurationMs);
            }

            //混合并调整音量
            adjustedPcm = new File(cacheDir, "adjusted_" + System.currentTimeMillis() + ".pcm");
            AudioUtil.mixPcm(videoPcmFile.getAbsolutePath(), aacPcmFile.getAbsolutePath(), adjustedPcm.getAbsolutePath()
                    , videoVolume, aacVolume);
            wavFile = new File(context.getCacheDir(), adjustedPcm.getName() + ".wav");
            long s4 = System.currentTimeMillis();

            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            if (channelCount == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
            //淡入淡出
            if (fadeInSec != 0 || fadeOutSec != 0) {
                AudioFadeUtil.audioFade(adjustedPcm.getAbsolutePath(), sampleRate, channelCount, fadeInSec, fadeOutSec);
            }
            //PCM转WAV
            new PcmToWavUtil(sampleRate, channelConfig, channelCount, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath(), wavFile.getAbsolutePath());
            long s5 = System.currentTimeMillis();
            CL.et("hwLog", String.format("decode:%dms,resample:%dms,mix:%dms,fade:%dms", s2 - s1, s3 - s2, s4 - s3, s5 - s4));
        } else {
            AudioUtil.decodeToPCM(aacInput, aacPcmFile.getAbsolutePath(), 0,
                    aacDurationMs > videoDurationMs ? videoDurationMs * 1000 : aacDurationMs * 1000);
            MediaFormat aacTrackFormat = aacExtractor.getTrackFormat(aacAudioIndex);
            audioBitrate = AudioUtil.getAudioBitrate(aacTrackFormat);
            muxerAudioIndex = mediaMuxer.addTrack(aacTrackFormat);

            sampleRate = aacTrackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = aacTrackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? aacTrackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (channelCount > 2) {
                File tempFile = new File(aacPcmFile + ".channel");
                AudioUtil.stereoToMonoSimple(aacPcmFile.getAbsolutePath(), tempFile.getAbsolutePath(), channelCount);
                channelCount = 1;
                aacPcmFile.delete();
                aacPcmFile = tempFile;
            }

            if (aacVolume != 50) {
                adjustedPcm = new File(cacheDir, "adjusted_" + System.currentTimeMillis() + ".pcm");
                AudioUtil.adjustPcmVolume(aacPcmFile.getAbsolutePath(), adjustedPcm.getAbsolutePath(), aacVolume);
            } else {
                adjustedPcm = aacPcmFile;
            }

            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            if (channelCount == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
            wavFile = new File(context.getCacheDir(), adjustedPcm.getName() + ".wav");
            //淡入淡出
            if (fadeInSec != 0 || fadeOutSec != 0) {
                AudioFadeUtil.audioFade(adjustedPcm.getAbsolutePath(), sampleRate, channelCount, fadeInSec, fadeOutSec);
            }
            //PCM转WAV
            new PcmToWavUtil(sampleRate, channelConfig, channelCount, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath(), wavFile.getAbsolutePath());
        }

        //重新写入音频
        mediaMuxer.start();

        MediaExtractor pcmExtrator = new MediaExtractor();
        pcmExtrator.setDataSource(wavFile.getAbsolutePath());
        int audioTrack = VideoUtil.selectTrack(pcmExtrator, true);
        pcmExtrator.selectTrack(audioTrack);
        MediaFormat pcmTrackFormat = pcmExtrator.getTrackFormat(audioTrack);
        int maxBufferSize = AudioUtil.getAudioMaxBufferSize(pcmTrackFormat);
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);//参数对应-> mime type、采样率、声道数
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);//比特率
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
                            detectTimeError = false;
                        }
                        if (info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                            lastAudioFrameTimeUs = info.presentationTimeUs;
                        }
                        mediaMuxer.writeSampleData(muxerAudioIndex, encodeOutputBuffer, info);

                        encodeOutputBuffer.clear();
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
            //重新将视频写入
            if (oriAudioIndex >= 0) {
                oriExtrator.unselectTrack(oriAudioIndex);
            }
            oriExtrator.selectTrack(oriVideoIndex);
            oriExtrator.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            maxBufferSize = oriVideoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            int frameRate = oriVideoFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? oriVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : (int) Math.ceil(VideoUtil.getAveFrameRate(videoInput));
            buffer = ByteBuffer.allocateDirect(maxBufferSize);
            final int VIDEO_FRAME_TIME_US = (int) (1000 * 1000f / frameRate);
            long lastVideoFrameTimeUs = -1;
            detectTimeError = false;
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
//                //写入视频
                if (!detectTimeError && lastVideoFrameTimeUs != -1 && info.presentationTimeUs < lastVideoFrameTimeUs + VIDEO_FRAME_TIME_US) {
                    //某些视频帧时间会出错
                    CL.et(TAG, "video 时间戳错误，lastVideoFrameTimeUs:" + lastVideoFrameTimeUs + " " +
                            "info.presentationTimeUs:" + info.presentationTimeUs + " VIDEO_FRAME_TIME_US:" + VIDEO_FRAME_TIME_US);
                    detectTimeError = true;
                }
                if (detectTimeError) {
                    info.presentationTimeUs = lastVideoFrameTimeUs + VIDEO_FRAME_TIME_US;
                    CL.et(TAG, "video 时间戳错误，使用修正的时间戳:" + info.presentationTimeUs);
                    detectTimeError = false;
                }
                if (info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    lastVideoFrameTimeUs = info.presentationTimeUs;
                }
                CL.wt(TAG, "video writeSampleData:" + info.presentationTimeUs + " type:" + info.flags + " size:" + info.size);
                mediaMuxer.writeSampleData(muxerVideoIndex, buffer, info);
                oriExtrator.advance();
            }
        } finally {
            aacPcmFile.delete();
            videoPcmFile.delete();
            adjustedPcm.delete();
            wavFile.delete();

            try {
                pcmExtrator.release();
                oriExtrator.release();
                mediaMuxer.release();
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                CL.e(e);
            }
        }
    }

    public static Processor processor(Context context) {
        return new Processor(context);
    }

    public static class Processor {
        private Context context;
        private String input;
        private String output;
        @Nullable
        private Integer outWidth;
        @Nullable
        private Integer outHeight;
        @Nullable
        private Integer startTimeMs;
        @Nullable
        private Integer endTimeMs;
        @Nullable
        private Float speed;
        @Nullable
        private Integer bitrate;
        @Nullable
        private Integer frameRate;
        @Nullable
        private Integer iFrameInterval;
        @Nullable
        private VideoProgressListener listener;

        public Processor(Context context) {
            this.context = context;
        }

        public Processor input(String input) {
            this.input = input;
            return this;
        }

        public Processor output(String output) {
            this.output = output;
            return this;
        }

        public Processor outWidth(int outWidth) {
            this.outWidth = outWidth;
            return this;
        }

        public Processor outHeight(int outHeight) {
            this.outHeight = outHeight;
            return this;
        }

        public Processor startTimeMs(int startTimeMs) {
            this.startTimeMs = startTimeMs;
            return this;
        }

        public Processor endTimeMs(int endTimeMs) {
            this.endTimeMs = endTimeMs;
            return this;
        }

        public Processor speed(float speed) {
            this.speed = speed;
            return this;
        }

        public Processor bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public Processor frameRate(int frameRate) {
            this.frameRate = frameRate;
            return this;
        }

        public Processor iFrameInterval(int iFrameInterval) {
            this.iFrameInterval = iFrameInterval;
            return this;
        }

        public Processor progressListener(VideoProgressListener listener) {
            this.listener = listener;
            return this;
        }

        public void process() throws Exception {
            processVideo(context, input, output, outWidth, outHeight, startTimeMs, endTimeMs, speed, bitrate, frameRate, iFrameInterval, listener);
        }
    }
}
