package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Pair;
import com.hw.videoprocessor.util.CL;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by huangwei on 2018/3/8 0008.
 */

public class VideoUtil {

    /**
     * @param inputVideo
     * @param outputDir
     * @param splitTimeMs
     * @param minSliceSize 如果最后一个片段小于一定值,就合并到前一个片段
     * @return
     */
    public static List<File> splitVideo(Context context, String inputVideo, String outputDir,
                                        int splitTimeMs, int minSliceSize,
                                        Integer bitrate, float speed, Integer iFrameInterval) throws Exception {
        splitTimeMs *= speed;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(inputVideo);
        int durationMs = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        int remainTimeMs = durationMs;
        List<Pair<Integer, Integer>> sliceList = new LinkedList<>();
        int sliceStartTimeMs = 0;
        //划分各片段时间
        while (remainTimeMs > 0) {
            remainTimeMs -= splitTimeMs;
            if (remainTimeMs < minSliceSize) {
                sliceList.add(new Pair<>(sliceStartTimeMs, sliceStartTimeMs + splitTimeMs + remainTimeMs));
                break;
            } else {
                sliceList.add(new Pair<>(sliceStartTimeMs, sliceStartTimeMs + splitTimeMs));
                sliceStartTimeMs += splitTimeMs;
            }
        }
        //开始切片
        List<File> fileList = new ArrayList<>(sliceList.size());
        for (Pair<Integer, Integer> pair : sliceList) {
            File file = new File(outputDir, pair.first + ".mp4");
            VideoProcessor.processVideo(context, inputVideo, file.getAbsolutePath(), null, null, pair.first,
                    pair.second, speed, bitrate, null, iFrameInterval);
            fileList.add(file);
        }
        return fileList;
    }

    /**
     * 合并片段,要求所有视频文件格式及参数必须一致
     *
     * @param inputVideos
     * @param outputVideo
     * @return
     * @throws IOException
     */
    public static void combineVideos(List<File> inputVideos, String outputVideo, Integer bitrate, Integer iFrameInterval) throws Exception {
        if (inputVideos == null || inputVideos.size() == 0) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(inputVideos.get(0).getAbsolutePath());
        int combineBitrate = bitrate == null ? Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)) : bitrate;
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputVideos.get(0).getAbsolutePath());
        int videoIndex = selectTrack(extractor, false);
        int audioIndex = selectTrack(extractor, true);

        MediaMuxer mediaMuxer = new MediaMuxer(outputVideo, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int videoMuxerIndex = mediaMuxer.addTrack(extractor.getTrackFormat(videoIndex));
        int audioMuxerIndex = 0;
        boolean audioExist = audioIndex >= 0;
        if (audioExist) {
            audioMuxerIndex = mediaMuxer.addTrack(extractor.getTrackFormat(audioIndex));
        }
        mediaMuxer.start();

        long videoFrameTimeUs;
        long audioFrameTimeUs = 0;
        long baseFrameTimeUs = 0;

        for (int i = 0; i < inputVideos.size(); i++) {
            if (i > 0) {
                extractor = new MediaExtractor();
                extractor.setDataSource(inputVideos.get(i).getAbsolutePath());
                audioIndex = selectTrack(extractor, true);
            }
            if (audioExist) {
                audioFrameTimeUs = writeAudioTrack(extractor, mediaMuxer, audioMuxerIndex, null, null, baseFrameTimeUs);
                extractor.unselectTrack(audioIndex);
            }
            videoFrameTimeUs = appendVideoTrack(extractor, mediaMuxer, videoMuxerIndex,
                    null, null, baseFrameTimeUs, combineBitrate,
                    iFrameInterval, i == 0, false);
            baseFrameTimeUs = videoFrameTimeUs > audioFrameTimeUs ? videoFrameTimeUs : audioFrameTimeUs;
            CL.i("片段" + i + "已合成,audioFrameTime:" + audioFrameTimeUs / 1000f + " videoFrameTime:" + videoFrameTimeUs / 1000f);
            baseFrameTimeUs += 33 * 1000;
            extractor.release();

            //反序当前片段
            long s1 = System.currentTimeMillis();
            String out = inputVideos.get(i).getAbsolutePath() + ".rev";
            VideoProcessor.revertVideoNoDecode(inputVideos.get(i).getAbsolutePath(), out);
            long e1 = System.currentTimeMillis();
            CL.e("revertVideoNoDecode:" + (e1 - s1) + "ms");
            //合并反序片段
            extractor = new MediaExtractor();
            extractor.setDataSource(out);
            if (audioExist) {
                audioIndex = selectTrack(extractor, true);
                audioFrameTimeUs = writeAudioTrack(extractor, mediaMuxer, audioMuxerIndex, null, null, baseFrameTimeUs);
                extractor.unselectTrack(audioIndex);
            }
            videoFrameTimeUs = appendVideoTrack(extractor, mediaMuxer, videoMuxerIndex, null, null,
                    baseFrameTimeUs, combineBitrate, iFrameInterval, false,
                    i == inputVideos.size() - 1);
            baseFrameTimeUs = videoFrameTimeUs > audioFrameTimeUs ? videoFrameTimeUs : audioFrameTimeUs;
            CL.i("反序片段" + i + "已合成,audioFrameTime:" + audioFrameTimeUs / 1000f + " videoFrameTime:" + videoFrameTimeUs / 1000f);
            baseFrameTimeUs += 33 * 1000;
            extractor.release();
            new File(out).delete();
        }
        mediaMuxer.release();
    }

    public static int selectTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    static long writeAudioTrack(MediaExtractor extractor, MediaMuxer mediaMuxer, int muxerAudioTrackIndex,
                                Integer startTimeUs, Integer endTimeUs) throws IOException {
        return writeAudioTrack(extractor, mediaMuxer, muxerAudioTrackIndex, startTimeUs, endTimeUs, 0);
    }

    /**
     * 不需要改变音频速率的情况下，直接读写就可
     */
    static long writeAudioTrack(MediaExtractor extractor, MediaMuxer mediaMuxer, int muxerAudioTrackIndex,
                                Integer startTimeUs, Integer endTimeUs, long baseMuxerFrameTimeUs) throws IOException {
        int audioTrack = selectTrack(extractor, true);
        extractor.selectTrack(audioTrack);
        if (startTimeUs == null) {
            startTimeUs = 0;
        }
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);
        int maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        long lastFrametimeUs = baseMuxerFrameTimeUs;
        while (true) {
            long sampleTimeUs = extractor.getSampleTime();
            if (sampleTimeUs == -1) {
                break;
            }
            if (sampleTimeUs < startTimeUs) {
                extractor.advance();
                continue;
            }
            if (endTimeUs != null && sampleTimeUs > endTimeUs) {
                break;
            }
            info.presentationTimeUs = sampleTimeUs - startTimeUs + baseMuxerFrameTimeUs;
            info.flags = extractor.getSampleFlags();
            info.size = extractor.readSampleData(buffer, 0);
            if (info.size < 0) {
                break;
            }
            CL.i("writeAudioSampleData,time:" + info.presentationTimeUs / 1000f);
            mediaMuxer.writeSampleData(muxerAudioTrackIndex, buffer, info);
            lastFrametimeUs = info.presentationTimeUs;
            extractor.advance();
        }
        return lastFrametimeUs;
    }

    static long appendVideoTrack(MediaExtractor extractor, MediaMuxer mediaMuxer, int muxerVideoTrackIndex,
                                 Integer startTimeUs, Integer endTimeUs, long baseMuxerFrameTimeUs, int bitrate, int iFrameInterval,
                                 boolean isFirst, boolean isLast) throws Exception {
        int videoTrack = selectTrack(extractor, false);
        extractor.selectTrack(videoTrack);
        if (startTimeUs == null) {
            startTimeUs = 0;
        }
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat videoFormat = extractor.getTrackFormat(videoTrack);

        //初始化编码器
        int resultWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int resultHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

        AtomicBoolean decodeDone = new AtomicBoolean(false);
        VideoAppendEncodeThread encodeThread = new VideoAppendEncodeThread(extractor, mediaMuxer, bitrate,
                resultWidth, resultHeight, iFrameInterval, videoTrack,
                decodeDone, baseMuxerFrameTimeUs, isFirst, isLast, muxerVideoTrackIndex);
        VideoDecodeThread decodeThread = new VideoDecodeThread(encodeThread, extractor, startTimeUs == null ? null : startTimeUs / 1000,
                endTimeUs == null ? null : endTimeUs / 1000,
                null, videoTrack, decodeDone);
        decodeThread.start();
        encodeThread.start();
        try {
            decodeThread.join();
            encodeThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            extractor.release();
        } catch (Exception e2) {
            CL.e(e2);
        }
        if (encodeThread.getException() != null) {
            throw encodeThread.getException();
        } else if (decodeThread.getException() != null) {
            throw decodeThread.getException();
        }
        return encodeThread.getLastFrametimeUs();
    }

    static int getAudioMaxBufferSize(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            return format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            return 100 * 1000;
        }
    }

    static int getAudioBitrate(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            return format.getInteger(MediaFormat.KEY_BIT_RATE);
        } else {
            return VideoProcessor.DEFAULT_AAC_BITRATE;
        }
    }

    /**
     * 用于制作全关键帧视频时计算比特率应该为多少
     *
     * @return
     */
    static int getBitrateForAllKeyFrameVideo(String input) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(input);
        int trackIndex = VideoUtil.selectTrack(extractor, false);
        extractor.selectTrack(trackIndex);
        int keyFrameCount = 0;
        int frameCount = 0;
        while (true) {
            int flags = extractor.getSampleFlags();
            if ((flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
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
        float bitrateMultiple = (frameCount - keyFrameCount) / (float) keyFrameCount;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        retriever.release();
        return (int) (bitrateMultiple * oriBitrate);
    }

    public static int getFrameRate(String videoPath) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoPath);
            int trackIndex = VideoUtil.selectTrack(extractor, false);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            return format.containsKey(MediaFormat.KEY_FRAME_RATE) ? format.getInteger(MediaFormat.KEY_FRAME_RATE) : -1;
        } catch (IOException e) {
            CL.e(e);
            return -1;
        } finally {
            extractor.release();
        }
    }

    public static void seekToLastFrame(MediaExtractor extractor, int trackIndex, int durationMs) {
        int seekToDuration = durationMs * 1000;
        if (extractor.getSampleTrackIndex() != trackIndex) {
            extractor.selectTrack(trackIndex);
        }
        extractor.seekTo(seekToDuration, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (seekToDuration > 0 && extractor.getSampleTrackIndex() != trackIndex) {
            seekToDuration -= 10000;
            extractor.seekTo(seekToDuration, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
    }
}
