package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Pair;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;

import java.io.File;
import java.io.IOException;
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
            VideoProcessor.processor(context)
                    .input(inputVideo)
                    .output(file.getAbsolutePath())
                    .startTimeMs(pair.first)
                    .endTimeMs(pair.second)
                    .speed(speed)
                    .bitrate(bitrate)
                    .iFrameInterval(iFrameInterval)
                    .process();
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
                audioFrameTimeUs = AudioUtil.writeAudioTrack(extractor, mediaMuxer, audioMuxerIndex, null, null, baseFrameTimeUs, null);
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
            VideoProcessor.reverseVideoNoDecode(inputVideos.get(i).getAbsolutePath(), out, true);
            long e1 = System.currentTimeMillis();
            CL.e("reverseVideoNoDecode:" + (e1 - s1) + "ms");
            //合并反序片段
            extractor = new MediaExtractor();
            extractor.setDataSource(out);
            if (audioExist) {
                audioIndex = selectTrack(extractor, true);
                audioFrameTimeUs = AudioUtil.writeAudioTrack(extractor, mediaMuxer, audioMuxerIndex, null, null, baseFrameTimeUs, null);
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
                null, null, null, false,videoTrack, decodeDone);
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

    /**
     * 用于制作全关键帧视频时计算比特率应该为多少
     *
     * @return
     */
    public static int getBitrateForAllKeyFrameVideo(String input) throws IOException {
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
        float bitrateMultiple = (frameCount - keyFrameCount) / (float) keyFrameCount + 1;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        retriever.release();
        if (frameCount == keyFrameCount) {
            return oriBitrate;
        }
        return (int) (bitrateMultiple * oriBitrate);
    }

    public static Pair<Integer, Integer> getVideoFrameCount(String input) throws IOException {
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
        return new Pair<>(keyFrameCount, frameCount);
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

    public static float getAveFrameRate(String videoPath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(videoPath);
        int trackIndex = VideoUtil.selectTrack(extractor, false);
        extractor.selectTrack(trackIndex);
        long lastSampleTimeUs = 0;
        int frameCount = 0;
        while (true) {
            long sampleTime = extractor.getSampleTime();
            if (sampleTime < 0) {
                break;
            } else {
                lastSampleTimeUs = sampleTime;
            }
            frameCount++;
            extractor.advance();
        }
        extractor.release();
        return frameCount / (lastSampleTimeUs / 1000f / 1000f);
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

    public static File getVideoCacheDir(Context context) {
        File cacheDir = new File(context.getCacheDir(), "video/");
        cacheDir.mkdirs();
        return cacheDir;
    }


    public static boolean trySetProfileAndLevel(MediaCodec codec, String mime, MediaFormat format, int profileInt, int levelInt) {
        MediaCodecInfo codecInfo = codec.getCodecInfo();
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mime);
        MediaCodecInfo.CodecProfileLevel[] profileLevels = capabilities.profileLevels;
        if (profileLevels == null) {
            return false;
        }
        for (MediaCodecInfo.CodecProfileLevel level : profileLevels) {
            if (level.profile == profileInt) {
                if (level.level == levelInt) {
                    format.setInteger(MediaFormat.KEY_PROFILE, profileInt);
                    format.setInteger(MediaFormat.KEY_LEVEL, levelInt);
                    return true;
                }
            }
        }
        return false;
    }

    public static int getMaxSupportBitrate(MediaCodec codec, String mime) {
        try {
            MediaCodecInfo codecInfo = codec.getCodecInfo();
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mime);
            Integer maxBitrate = capabilities.getVideoCapabilities().getBitrateRange().getUpper();
            return maxBitrate;
        } catch (Exception e) {
            CL.e(e);
            return -1;
        }
    }

    static List<Long> getFrameTimeStampsList(MediaExtractor extractor){
        List<Long> frameTimeStamps = new ArrayList<>();
        while (true) {
            long sampleTime = extractor.getSampleTime();
            if (sampleTime < 0) {
                break;
            }
            frameTimeStamps.add(sampleTime);
            extractor.advance();
        }
        return frameTimeStamps;
    }
}
