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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hw.videoprocessor.util.AudioUtil.getAudioBitrate;

/**
 * Created by huangwei on 2018/2/2.
 */
@TargetApi(21)
public class VideoProcessor {
    final static String TAG = "VideoProcessor";
    final static String OUTPUT_MIME_TYPE = "video/avc";

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
    public static void reverseVideo(Context context, String input, String output, boolean reverseAudio, @Nullable VideoProgressListener listener) throws Exception {
        File tempFile = new File(context.getCacheDir(), System.currentTimeMillis() + ".temp");
        File temp2File = new File(context.getCacheDir(), System.currentTimeMillis() + ".temp2");
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(input);
            int trackIndex = VideoUtil.selectTrack(extractor, false);
            extractor.selectTrack(trackIndex);
            int keyFrameCount = 0;
            int frameCount = 0;
            List<Long> frameTimeStamps = new ArrayList<>();
            while (true) {
                int flags = extractor.getSampleFlags();
                if (flags > 0 && (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyFrameCount++;
                }
                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0) {
                    break;
                }
                frameTimeStamps.add(sampleTime);
                frameCount++;
                extractor.advance();
            }
            extractor.release();

            if (frameCount == keyFrameCount || frameCount == keyFrameCount + 1) {
                reverseVideoNoDecode(input, output, reverseAudio, frameTimeStamps, listener);
            } else {
                VideoMultiStepProgress stepProgress = new VideoMultiStepProgress(new float[]{0.45f, 0.1f, 0.45f}, listener);
                stepProgress.setCurrentStep(0);
                float bitrateMultiple = (frameCount - keyFrameCount) / (float) keyFrameCount + 1;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(input);
                int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                int duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                try {
                    processor(context)
                            .input(input)
                            .output(tempFile.getAbsolutePath())
                            .bitrate((int) (oriBitrate * bitrateMultiple))
                            .iFrameInterval(0)
                            .progressListener(stepProgress)
                            .process();
                } catch (MediaCodec.CodecException e) {
                    CL.e(e);
                    /** Nexus5上-1代表全关键帧*/
                    processor(context)
                            .input(input)
                            .output(tempFile.getAbsolutePath())
                            .bitrate((int) (oriBitrate * bitrateMultiple))
                            .iFrameInterval(-1)
                            .progressListener(stepProgress)
                            .process();
                }
                stepProgress.setCurrentStep(1);
                reverseVideoNoDecode(tempFile.getAbsolutePath(), temp2File.getAbsolutePath(), reverseAudio, null, stepProgress);
                int oriIFrameInterval = (int) (keyFrameCount / (duration / 1000f));
                oriIFrameInterval = oriIFrameInterval == 0 ? 1 : oriIFrameInterval;
                stepProgress.setCurrentStep(2);
                processor(context)
                        .input(temp2File.getAbsolutePath())
                        .output(output)
                        .bitrate(oriBitrate)
                        .iFrameInterval(oriIFrameInterval)
                        .progressListener(stepProgress)
                        .process();
            }
        } finally {
            tempFile.delete();
            temp2File.delete();
        }
    }

    /**
     * 支持裁剪缩放快慢放
     */
    public static void processVideo(@NonNull Context context, @NonNull Processor processor) throws Exception {

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(processor.input);
        int originWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int originHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rotationValue = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        int durationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();
        if (processor.bitrate == null) {
            processor.bitrate = oriBitrate;
        }
        if (processor.iFrameInterval == null) {
            processor.iFrameInterval = DEFAULT_I_FRAME_INTERVAL;
        }

        int resultWidth = processor.outWidth == null ? originWidth : processor.outWidth;
        int resultHeight = processor.outHeight == null ? originHeight : processor.outHeight;
        resultWidth = resultWidth % 2 == 0 ? resultWidth : resultWidth + 1;
        resultHeight = resultHeight % 2 == 0 ? resultHeight : resultHeight + 1;

        if (rotationValue == 90 || rotationValue == 270) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(processor.input);
        int videoIndex = VideoUtil.selectTrack(extractor, false);
        int audioIndex = VideoUtil.selectTrack(extractor, true);
        MediaMuxer mediaMuxer = new MediaMuxer(processor.output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxerAudioTrackIndex = 0;
        boolean shouldChangeAudioSpeed = processor.changeAudioSpeed == null ? true : processor.changeAudioSpeed;
        Integer audioEndTimeMs = processor.endTimeMs;
        if (audioIndex >= 0) {
            MediaFormat audioTrackFormat = extractor.getTrackFormat(audioIndex);
            String audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC;
            int bitrate = getAudioBitrate(audioTrackFormat);
            int channelCount = audioTrackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int sampleRate = audioTrackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int maxBufferSize = AudioUtil.getAudioMaxBufferSize(audioTrackFormat);
            MediaFormat audioEncodeFormat = MediaFormat.createAudioFormat(audioMimeType, sampleRate, channelCount);//参数对应-> mime type、采样率、声道数
            audioEncodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);//比特率
            audioEncodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioEncodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxBufferSize);

            if (shouldChangeAudioSpeed) {
                if (processor.startTimeMs != null || processor.endTimeMs != null || processor.speed != null) {
                    long durationUs = audioTrackFormat.getLong(MediaFormat.KEY_DURATION);
                    if (processor.startTimeMs != null && processor.endTimeMs != null) {
                        durationUs = (processor.endTimeMs - processor.startTimeMs) * 1000;
                    }
                    if (processor.speed != null) {
                        durationUs /= processor.speed;
                    }
                    audioEncodeFormat.setLong(MediaFormat.KEY_DURATION, durationUs);
                }
            } else {
                long videoDurationUs = durationMs * 1000;
                long audioDurationUs = audioTrackFormat.getLong(MediaFormat.KEY_DURATION);

                if (processor.startTimeMs != null || processor.endTimeMs != null || processor.speed != null) {
                    if (processor.startTimeMs != null && processor.endTimeMs != null) {
                        videoDurationUs = (processor.endTimeMs - processor.startTimeMs) * 1000;
                    }
                    if (processor.speed != null) {
                        videoDurationUs /= processor.speed;
                    }
                    long avDurationUs = videoDurationUs < audioDurationUs ? videoDurationUs : audioDurationUs;
                    audioEncodeFormat.setLong(MediaFormat.KEY_DURATION, avDurationUs);
                    audioEndTimeMs = (processor.startTimeMs == null ? 0 : processor.startTimeMs) + (int) (avDurationUs / 1000);
                }
            }

            AudioUtil.checkCsd(audioEncodeFormat,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    sampleRate,
                    channelCount
            );
            //提前推断出音頻格式加到MeidaMuxer，不然实际上应该到音频预处理完才能addTrack，会卡住视频编码的进度
            muxerAudioTrackIndex = mediaMuxer.addTrack(audioEncodeFormat);
        }
        extractor.selectTrack(videoIndex);
        if (processor.startTimeMs != null) {
            extractor.seekTo(processor.startTimeMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        } else {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        VideoProgressAve progressAve = new VideoProgressAve(processor.listener);
        progressAve.setSpeed(processor.speed);
        progressAve.setStartTimeMs(processor.startTimeMs == null ? 0 : processor.startTimeMs);
        progressAve.setEndTimeMs(processor.endTimeMs == null ? durationMs : processor.endTimeMs);
        AtomicBoolean decodeDone = new AtomicBoolean(false);
        CountDownLatch muxerStartLatch = new CountDownLatch(1);
        VideoEncodeThread encodeThread = new VideoEncodeThread(extractor, mediaMuxer,processor.bitrate,
                resultWidth, resultHeight, processor.iFrameInterval, processor.frameRate == null ? DEFAULT_FRAME_RATE : processor.frameRate, videoIndex,
                decodeDone, muxerStartLatch);
        int srcFrameRate = VideoUtil.getFrameRate(processor.input);
        if (srcFrameRate <= 0) {
            srcFrameRate = (int) Math.ceil(VideoUtil.getAveFrameRate(processor.input));
        }
        VideoDecodeThread decodeThread = new VideoDecodeThread(encodeThread, extractor, processor.startTimeMs, processor.endTimeMs, srcFrameRate,
                processor.frameRate == null ? DEFAULT_FRAME_RATE : processor.frameRate, processor.speed, processor.dropFrames, videoIndex, decodeDone);

        AudioProcessThread audioProcessThread = new AudioProcessThread(context, processor.input, mediaMuxer, processor.startTimeMs, audioEndTimeMs,
                shouldChangeAudioSpeed ? processor.speed : null, muxerAudioTrackIndex, muxerStartLatch);
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

    public static void reverseVideoNoDecode(String input, String output, boolean reverseAudio) throws IOException {
        reverseVideoNoDecode(input, output, reverseAudio, null, null);
    }

    /**
     * 直接对视频进行逆序,用于所有帧都是关键帧的情况
     */
    public static void reverseVideoNoDecode(String input, String output, boolean reverseAudio, List<Long> videoFrameTimeStamps, @Nullable VideoProgressListener listener) throws IOException {
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
            if (videoFrameTimeStamps != null && videoFrameTimeStamps.size() > 0) {
                for (int i = videoFrameTimeStamps.size() - 1; i >= 0; i--) {
                    extractor.seekTo(videoFrameTimeStamps.get(i), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
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
                }
            } else {
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
            }
            //写音频帧
            if (audioExist) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                if (reverseAudio) {
                    List<Long> audioFrameStamps = VideoUtil.getFrameTimeStampsList(extractor);
                    lastFrameTimeUs = -1;
                    for (int i = audioFrameStamps.size() - 1; i >= 0; i--) {
                        extractor.seekTo(audioFrameStamps.get(i), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
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
                    }
                } else {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    while (true) {
                        long sampleTime = extractor.getSampleTime();
                        if (sampleTime == -1) {
                            break;
                        }
                        info.presentationTimeUs = sampleTime;
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
                        extractor.advance();
                    }
                }
            }
            if (listener != null) {
                listener.onProgress(1f);
            }
        } catch (Exception e) {
            CL.e(e);
        } finally {
            extractor.release();
            mediaMuxer.release();
        }
    }

    /**
     * 不需要改变音频速率的情况下，直接读写就可
     * 只支持16bit音频
     *
     * @param videoVolume 0静音，100表示原音
     */
    public static void adjustVideoVolume(Context context, final String videoInput, final String output,
                                         @IntRange(from = 0, to = 100) int videoVolume, float faceInSec, float fadeOutSec) throws IOException {
        if (videoVolume == 100 && faceInSec == 0f && fadeOutSec == 0f) {
            AudioUtil.copyFile(videoInput, output);
            return;
        }
        File cacheDir = new File(context.getCacheDir(), "pcm");
        cacheDir.mkdir();

        MediaExtractor oriExtrator = new MediaExtractor();
        oriExtrator.setDataSource(videoInput);
        int oriAudioIndex = VideoUtil.selectTrack(oriExtrator, true);
        if (oriAudioIndex < 0) {
            CL.e("no audio stream!");
            AudioUtil.copyFile(videoInput, output);
            return;
        }
        long time = System.currentTimeMillis();
        final File videoPcmFile = new File(cacheDir, "video_" + time + ".pcm");
        final File videoPcmAdjustedFile = new File(cacheDir, "video_" + time + "_adjust.pcm");
        final File videoWavFile = new File(cacheDir, "video_" + time + ".wav");

        AudioUtil.decodeToPCM(videoInput, videoPcmFile.getAbsolutePath(), null, null);
        AudioUtil.adjustPcmVolume(videoPcmFile.getAbsolutePath(), videoPcmAdjustedFile.getAbsolutePath(), videoVolume);

        MediaFormat audioTrackFormat = oriExtrator.getTrackFormat(oriAudioIndex);
        final int sampleRate = audioTrackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = audioTrackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? audioTrackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (channelCount == 2) {
            channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }
        if (faceInSec > 0 || fadeOutSec > 0) {
            AudioFadeUtil.audioFade(videoPcmAdjustedFile.getAbsolutePath(), sampleRate, channelCount, faceInSec, fadeOutSec);
        }
        new PcmToWavUtil(sampleRate, channelConfig, channelCount, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(videoPcmAdjustedFile.getAbsolutePath(), videoWavFile.getAbsolutePath());

        final int TIMEOUT_US = 2500;
        //重新将速率变化过后的pcm写入
        int audioBitrate = getAudioBitrate(audioTrackFormat);

        int oriVideoIndex = VideoUtil.selectTrack(oriExtrator, false);
        MediaFormat oriVideoFormat = oriExtrator.getTrackFormat(oriVideoIndex);
        int rotation = oriVideoFormat.containsKey(MediaFormat.KEY_ROTATION) ? oriVideoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mediaMuxer.setOrientationHint(rotation);
        int muxerVideoIndex = mediaMuxer.addTrack(oriVideoFormat);
        int muxerAudioIndex = mediaMuxer.addTrack(audioTrackFormat);

        //重新写入音频
        mediaMuxer.start();

        MediaExtractor pcmExtrator = new MediaExtractor();
        pcmExtrator.setDataSource(videoWavFile.getAbsolutePath());
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
            oriExtrator.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
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
                info.presentationTimeUs = sampleTimeUs;
                info.flags = oriExtrator.getSampleFlags();
                info.size = oriExtrator.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }
                //写入视频
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
            videoPcmFile.delete();
            videoPcmAdjustedFile.delete();
            videoWavFile.delete();

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

    /**
     * 不需要改变音频速率的情况下，直接读写就可
     * 只支持16bit音频
     *
     * @param videoVolume 0静音，100表示原音
     * @param aacVolume   0静音，100表示原音
     */
    public static void mixAudioTrack(Context context, final String videoInput, final String audioInput, final String output,
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
        retriever.setDataSource(audioInput);
        final int aacDurationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();

        MediaExtractor oriExtrator = new MediaExtractor();
        oriExtrator.setDataSource(videoInput);
        int oriAudioIndex = VideoUtil.selectTrack(oriExtrator, true);
        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(audioInput);
        int aacAudioIndex = VideoUtil.selectTrack(audioExtractor, true);
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
                        AudioUtil.decodeToPCM(audioInput, finalAacPcmFile.getAbsolutePath(), 0, aacDurationMs > videoDurationMs ? videoDurationMs * 1000 : aacDurationMs * 1000);
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


            //检查两段音频格式是否一致,不一致则统一转换为单声道+44100
            Pair<Integer, Integer> resultPair = AudioUtil.checkAndAdjustAudioFormat(videoPcmFile.getAbsolutePath(),
                    aacPcmFile.getAbsolutePath(),
                    oriExtrator.getTrackFormat(oriAudioIndex),
                    audioExtractor.getTrackFormat(aacAudioIndex)
            );
            channelCount = resultPair.first;
            sampleRate = resultPair.second;
            audioExtractor.release();
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
            MediaFormat oriAudioFormat = oriExtrator.getTrackFormat(oriAudioIndex);
            audioBitrate = getAudioBitrate(oriAudioFormat);
            oriAudioFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
            AudioUtil.checkCsd(oriAudioFormat,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    sampleRate,
                    channelCount
            );
            muxerAudioIndex = mediaMuxer.addTrack(oriAudioFormat);
        } else {
            AudioUtil.decodeToPCM(audioInput, aacPcmFile.getAbsolutePath(), 0,
                    aacDurationMs > videoDurationMs ? videoDurationMs * 1000 : aacDurationMs * 1000);
            MediaFormat audioTrackFormat = audioExtractor.getTrackFormat(aacAudioIndex);
            audioBitrate = getAudioBitrate(audioTrackFormat);
            channelCount = audioTrackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ?
                    audioTrackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            sampleRate = audioTrackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ?
                    audioTrackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            if (channelCount == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
            AudioUtil.checkCsd(audioTrackFormat,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    sampleRate,
                    channelCount);
            audioTrackFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
            muxerAudioIndex = mediaMuxer.addTrack(audioTrackFormat);

            sampleRate = audioTrackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = audioTrackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? audioTrackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
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

            channelConfig = AudioFormat.CHANNEL_IN_MONO;
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
                //写入视频
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
                encoder.stop();
                encoder.release();
                mediaMuxer.release();
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
        private Boolean changeAudioSpeed;
        @Nullable
        private Integer bitrate;
        @Nullable
        private Integer frameRate;
        @Nullable
        private Integer iFrameInterval;
        @Nullable
        private VideoProgressListener listener;
        /**
         * 帧率超过指定帧率时是否丢帧
         */
        private boolean dropFrames = true;

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

        public Processor changeAudioSpeed(boolean changeAudioSpeed) {
            this.changeAudioSpeed = changeAudioSpeed;
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

        /**
         * 帧率超过指定帧率时是否丢帧,默认为true
         */
        public Processor dropFrames(boolean dropFrames) {
            this.dropFrames = dropFrames;
            return this;
        }

        public Processor progressListener(VideoProgressListener listener) {
            this.listener = listener;
            return this;
        }

        public void process() throws Exception {
            processVideo(context, this);
        }
    }
}
