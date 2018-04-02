package com.hw.videoprocessor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Pair;
import android.view.Surface;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.InputSurface;
import com.hw.videoprocessor.util.OutputSurface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

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
    public static List<File> splitVideo(Context context, String inputVideo, String outputDir, int splitTimeMs, int minSliceSize, Integer bitrate, Integer iFrameInterval) throws IOException {
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
                    pair.second, null, bitrate, iFrameInterval);
            AudioUtil.copyFile(file.getAbsolutePath(), "/mnt/sdcard/slice_" + pair.first + ".mp4");
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
    public static void combineVideos(List<File> inputVideos, String outputVideo) throws IOException {
        if (inputVideos == null || inputVideos.size() == 0) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(inputVideos.get(0).getAbsolutePath());
        int bitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
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
            videoFrameTimeUs = appendVideoTrack(extractor, mediaMuxer, videoMuxerIndex, null, null, baseFrameTimeUs, bitrate, i == 0, false);
            baseFrameTimeUs = videoFrameTimeUs > audioFrameTimeUs ? videoFrameTimeUs : audioFrameTimeUs;
            CL.i("片段" + i + "已合成,audioFrameTime:" + audioFrameTimeUs / 1000f + " videoFrameTime:" + videoFrameTimeUs / 1000f);
            baseFrameTimeUs += 33 * 1000;
            extractor.release();

            //反序当前片段
            String out = inputVideos.get(i).getAbsolutePath() + ".rev";
            VideoProcessor.revertVideoNoDecode(inputVideos.get(i).getAbsolutePath(), out);
            //合并反序片段
            extractor = new MediaExtractor();
            extractor.setDataSource(out);
            if (audioExist) {
                audioIndex = selectTrack(extractor, true);
                audioFrameTimeUs = writeAudioTrack(extractor, mediaMuxer, audioMuxerIndex, null, null, baseFrameTimeUs);
                extractor.unselectTrack(audioIndex);
            }
            videoFrameTimeUs = appendVideoTrack(extractor, mediaMuxer, videoMuxerIndex, null, null, baseFrameTimeUs, bitrate, false, i == inputVideos.size() - 1);
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
                                 Integer startTimeUs, Integer endTimeUs, long baseMuxerFrameTimeUs, int bitrate, boolean isFirst, boolean isLast) throws IOException {
        int videoTrack = selectTrack(extractor, false);
        extractor.selectTrack(videoTrack);
        if (startTimeUs == null) {
            startTimeUs = 0;
        }
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        MediaFormat videoFormat = extractor.getTrackFormat(videoTrack);


        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        MediaCodec decoder = null;
        MediaCodec encoder = null;
        //初始化编码器
        int resultWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int resultHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

        MediaFormat outputFormat = MediaFormat.createVideoFormat(VideoProcessor.MIME_TYPE, resultWidth, resultHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VideoProcessor.DEFAULT_FRAME_RATE);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoProcessor.DEFAULT_I_FRAME_INTERVAL);

        encoder = MediaCodec.createEncoderByType(VideoProcessor.MIME_TYPE);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = encoder.createInputSurface();
        InputSurface inputSurface = new InputSurface(surface);
        inputSurface.makeCurrent();

        //初始化解码器
        MediaFormat inputFormat = videoFormat;
        decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        OutputSurface outputSurface = new OutputSurface();
        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);

        decoder.start();
        encoder.start();

        long lastFrametimeUs = baseMuxerFrameTimeUs;
        final int TIMEOUT_USEC = 2500;
        long videoStartTimeUs = -1;

        try {
            //开始解码
            boolean encoderDone = false;
            boolean decoderDone = false;
            boolean inputDone = false;
            boolean signalEncodeEnd = false;
            int encodeTryAgainCount = 0;

            while (!decoderDone || !encoderDone) {
                //还有帧数据，输入解码器
                if (!inputDone) {
                    boolean eof = false;
                    int index = extractor.getSampleTrackIndex();
                    if (index == videoTrack) {
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
                        CL.i("inputDone");
                        int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                        if (inputBufIndex >= 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }
                boolean decoderOutputAvailable = !decoderDone;
                if (decoderDone) {
                    CL.i("decoderOutputAvailable:" + decoderOutputAvailable);
                }
                while (decoderOutputAvailable) {
                    int outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    CL.i("outputBufferIndex = " + outputBufferIndex);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        CL.i("decode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        boolean doRender = true;
                        //解码数据可用
                        if (endTimeUs != null && info.presentationTimeUs >= endTimeUs) {
                            inputDone = true;
                            decoderDone = true;
                            doRender = false;
                            info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        }
                        if (startTimeUs != null && info.presentationTimeUs < startTimeUs) {
                            doRender = false;
                            CL.e("drop frame startTime = " + startTimeUs / 1000f + " present time = " + info.presentationTimeUs / 1000);
                        }
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            decoderDone = true;
                            decoder.releaseOutputBuffer(outputBufferIndex, false);
                            CL.i("decoderDone");
                            break;
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, true);
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
                                outputSurface.drawImage(false);
                                long presentationTimeNs = (info.presentationTimeUs - videoStartTimeUs) * 1000;
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
                    CL.i("encode outputBufferIndex = " + outputBufferIndex);
                    if (signalEncodeEnd && outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        encodeTryAgainCount++;
                        if (encodeTryAgainCount > 10) {
                            //三星S8上出现signalEndOfInputStream之后一直tryAgain的问题
                            encoderDone = true;
                            CL.e("INFO_TRY_AGAIN_LATER 10 times,force End!");
                            break;
                        }
                    } else {
                        encodeTryAgainCount = 0;
                    }
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        CL.i("encode newFormat = " + newFormat);
                    } else if (outputBufferIndex < 0) {
                        //ignore
                        CL.e("unexpected result from decoder.dequeueOutputBuffer: " + outputBufferIndex);
                    } else {
                        //编码数据可用
                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        info.presentationTimeUs += baseMuxerFrameTimeUs;
                        if (!isFirst && info.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                            //非第一个片段跳过写入Config
                            continue;
                        }
                        if (!isLast && info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            //非最后一个片段不写入End
                            encoderDone = true;
                            CL.i("encoderDone");
                            encoder.releaseOutputBuffer(outputBufferIndex, false);
                            break;
                        }

                        mediaMuxer.writeSampleData(muxerVideoTrackIndex, outputBuffer, info);
                        lastFrametimeUs = info.presentationTimeUs;
                        CL.i("writeSampleData,size:" + info.size + " time:" + info.presentationTimeUs / 1000f + " flag:" + info.flags);

                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                        if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            encoderDone = true;
                            CL.i("encoderDone");
                            break;
                        }
                    }
                }
            }
            encoder.stop();
            decoder.stop();
        } catch (Exception e) {
            CL.e(e);
        } finally {
            encoder.release();
            decoder.release();
        }
        return lastFrametimeUs;
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
        float bitrateMultiple = (frameCount - keyFrameCount) / (float) keyFrameCount;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(input);
        int oriBitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        retriever.release();
        return (int) (bitrateMultiple * oriBitrate);
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
