package com.hw.videoprocessor.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.support.annotation.IntRange;
import android.util.Pair;
import com.hw.videoprocessor.VideoUtil;
import com.hw.videoprocessor.jssrc.SSRC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Created by huangwei on 2018/3/7 0007.
 */

public class AudioUtil {
    final static String TAG = "VideoProcessor";
    public static int VOLUMN_MAX_RATIO = 1;

    public static void adjustPcmVolume(String fromPath, String toPath, @IntRange(from = 0, to = 100) int volume) throws IOException {
        if (volume == 100) {
            copyFile(fromPath, toPath);
            return;
        }
        float vol = normalizeVolume(volume);

        byte[] buffer = new byte[2048];
        FileInputStream fileInputStream = new FileInputStream(fromPath);
        FileOutputStream fileOutputStream = new FileOutputStream(toPath);

        int tmp;
        try {
            while (fileInputStream.read(buffer) != -1) {
                for (int i = 0; i < buffer.length; i += 2) {
                    tmp = (short) ((buffer[i] & 0xff) | (buffer[i + 1] & 0xff) << 8);
                    tmp *= vol;
                    if (tmp > 32767) {
                        tmp = 32767;
                    } else if (tmp < -32768) {
                        tmp = -32768;
                    }
                    buffer[i] = (byte) (tmp & 0xFF);
                    buffer[i + 1] = (byte) ((tmp >>> 8) & 0xFF);
                }
                fileOutputStream.write(buffer);
            }
        } finally {
            fileInputStream.close();
            fileOutputStream.close();
        }
    }

    /**
     * @param volume
     * @return 0~100 -> 0~1
     */
    private static float normalizeVolume(@IntRange(from = 0, to = 100) int volume) {
        return volume / 100f * VOLUMN_MAX_RATIO;
    }

    public static void mixPcm(String pcm1Path, String pcm2Path, String toPath
            , @IntRange(from = 0, to = 100) int volume1
            , @IntRange(from = 0, to = 100) int volume2) throws IOException {
        float vol1 = normalizeVolume(volume1);
        float vol2 = normalizeVolume(volume2);

        byte[] buffer1 = new byte[2048];
        byte[] buffer2 = new byte[2048];
        byte[] buffer3 = new byte[2048];

        FileInputStream is1 = new FileInputStream(pcm1Path);
        FileInputStream is2 = new FileInputStream(pcm2Path);

        FileOutputStream fileOutputStream = new FileOutputStream(toPath);

        boolean end1 = false, end2 = false;
        short temp2, temp1;
        int temp;
        try {
            while (!end1 || !end2) {
                if (!end1) {
                    end1 = (is1.read(buffer1) == -1);
                    System.arraycopy(buffer1, 0, buffer3, 0, buffer1.length);
                }
                if (!end2) {
                    end2 = (is2.read(buffer2) == -1);
                    for (int i = 0; i < buffer2.length; i += 2) {
                        temp1 = (short) ((buffer1[i] & 0xff) | (buffer1[i + 1] & 0xff) << 8);
                        temp2 = (short) ((buffer2[i] & 0xff) | (buffer2[i + 1] & 0xff) << 8);
                        temp = (int) (temp2 * vol2 + temp1 * vol1);
                        if (temp > 32767) {
                            temp = 32767;
                        } else if (temp < -32768) {
                            temp = -32768;
                        }
                        buffer3[i] = (byte) (temp & 0xFF);
                        buffer3[i + 1] = (byte) ((temp >>> 8) & 0xFF);
                    }
                }
                fileOutputStream.write(buffer3);
            }
        } finally {
            is1.close();
            is2.close();
            fileOutputStream.close();
        }
    }

    public static void stereoToMono(String from, String to) throws IOException {
        stereoToMonoSimple(from, to, 2);
    }

    /**
     * 多声道转单声道,只取第一条声道
     *
     * @param from
     * @param to
     * @param srcChannelCount
     * @throws IOException
     */
    public static void stereoToMonoSimple(String from, String to, @IntRange(from = 2) int srcChannelCount) throws IOException {
        FileInputStream is = new FileInputStream(from);
        FileOutputStream os = new FileOutputStream(to);
        byte[] buffer1 = new byte[1024 * srcChannelCount];
        byte[] buffer2 = new byte[1024];

        while (is.read(buffer1) != -1) {
            for (int i = 0; i < buffer2.length; i += 2) {
                buffer2[i] = buffer1[srcChannelCount * i];
                buffer2[i + 1] = buffer1[srcChannelCount * i + 1];
            }
            os.write(buffer2);
        }
        is.close();
        os.close();
    }


    public static void copyFile(String from, String to) throws IOException {
        FileChannel toChannel = new FileOutputStream(to).getChannel();
        FileChannel fromChannel = new FileInputStream(from).getChannel();
        fromChannel.transferTo(0, fromChannel.size(), toChannel);
    }

    public static boolean isStereo(String aacPath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(aacPath);
        MediaFormat format = null;
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio/")) {
                break;
            }
        }
        extractor.release();
        if (format == null) {
            return false;
        }
        return format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 1;
    }

    /**
     * 检查两段音频格式是否一致,不一致则统一转换为单声道+44100
     */
    public static Pair<Integer, Integer> checkAndAdjustAudioFormat(String pcm1, String pcm2, MediaFormat format1, MediaFormat format2) {
        final int DEFAULT_SAMPLE_RATE = 44100;
        int channelCount1 = format1.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format1.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        int channelCount2 = format2.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format2.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        int sampleRate1 = format1.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format1.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;
        int sampleRate2 = format2.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format2.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;

        if (channelCount1 == channelCount2 && sampleRate1 == sampleRate2 && channelCount1 <= 2) {
            return new Pair<>(channelCount1, sampleRate1);
        }
        File temp1 = new File(pcm1 + ".temp");
        File temp2 = new File(pcm2 + ".temp");
        int channelCount = channelCount1;
        int sampleRate = sampleRate1;
        //声道不一样，全部转换为单声道
        try {
            if (channelCount1 != channelCount2 || channelCount1 > 2 || channelCount2 > 2) {
                if (channelCount1 > 1) {
                    stereoToMonoSimple(pcm1, temp1.getAbsolutePath(), channelCount1);
                    File file = new File(pcm1);
                    file.delete();
                    temp1.renameTo(file);
                    channelCount1 = 1;
                }
                if (channelCount2 > 1) {
                    stereoToMonoSimple(pcm2, temp2.getAbsolutePath(), channelCount2);
                    File file = new File(pcm2);
                    file.delete();
                    temp2.renameTo(file);
                    channelCount2 = 1;
                }
                channelCount = 1;
            } else {
                channelCount = channelCount1;
            }
            if (sampleRate1 != sampleRate2) {
                sampleRate = DEFAULT_SAMPLE_RATE;
                if (sampleRate1 != DEFAULT_SAMPLE_RATE) {
                    reSamplePcm(pcm1, temp1.getAbsolutePath(), sampleRate1, DEFAULT_SAMPLE_RATE, channelCount1);
                    File file = new File(pcm1);
                    file.delete();
                    temp1.renameTo(file);
                }
                if (sampleRate2 != DEFAULT_SAMPLE_RATE) {
                    reSamplePcm(pcm2, temp2.getAbsolutePath(), sampleRate2, DEFAULT_SAMPLE_RATE, channelCount2);
                    File file = new File(pcm2);
                    file.delete();
                    temp2.renameTo(file);
                }
            }
            return new Pair<>(channelCount, sampleRate);
        } catch (Exception e) {
            e.printStackTrace();
            return new Pair<>(channelCount, sampleRate);
        } finally {
            temp1.delete();
            temp2.exists();
        }
    }

    public static File checkAndFillPcm(File aacPcmFile, int pcmDuration, int fileToDuration) {
        if (pcmDuration >= fileToDuration) {
            return aacPcmFile;
        }
        File cacheFile = new File(aacPcmFile.getAbsolutePath() + ".concat");
        FileInputStream is = null;
        FileOutputStream os = null;
        FileChannel from = null;
        FileChannel to = null;
        try {
            //计算填充次数
            float repeat = fileToDuration / (float) pcmDuration;
            int repeatInt = (int) repeat;
            //拼接repeatInt次
            is = new FileInputStream(aacPcmFile);
            os = new FileOutputStream(cacheFile);
            from = is.getChannel();
            to = os.getChannel();
            for (int i = 0; i < repeatInt; i++) {
                from.transferTo(0, from.size(), to);
                from.position(0);
            }
            //剩下的部分
            float remain = repeat - repeatInt;
            int remainSize = (int) (aacPcmFile.length() * remain);
            if (remainSize > 1024) {
                from.transferTo(0, remainSize, to);
            }
            from.close();
            to.close();
            aacPcmFile.delete();
            cacheFile.renameTo(aacPcmFile);
            return aacPcmFile;
        } catch (Exception e) {
            e.printStackTrace();
            cacheFile.delete();
            return aacPcmFile;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                if (os != null) {
                    os.close();
                }
                if (from != null) {
                    from.close();
                }
                if (to != null) {
                    to.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 需要改变音频速率的情况下，需要先解码->改变速率->编码
     */
    public static void decodeToPCM(String audioPath, String outPath, Integer startTimeUs, Integer endTimeUs) throws IOException {
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

    public static boolean reSamplePcm(String srcPath, String dstPath, int srcSampleRate, int dstSampleRate, int srcChannelCount) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(srcPath);
            fos = new FileOutputStream(dstPath);
            new SSRC(fis, fos, srcSampleRate, dstSampleRate, 2, 2, srcChannelCount, (int) new File(srcPath).length(), 0, 0, true);
        } catch (IOException e) {
            CL.e(e);
            e.printStackTrace();
        }

        return true;
    }

    public static void reversePcm(String srcPath, String dstPath) throws IOException {
        final int bit = 16;
        RandomAccessFile srcFile = null;
        FileOutputStream fos = null;
        try {
            srcFile = new RandomAccessFile(srcPath, "r");
            fos = new FileOutputStream(dstPath);
            int step = bit / 8;
            long len = srcFile.length();
            long offset = len - step;
            byte temp[] = new byte[step];
            while (offset >= 0) {
                srcFile.seek(offset);
                srcFile.read(temp);
                fos.write(temp);
                offset-=step;
            }
        } finally {
            if (srcFile != null) {
                srcFile.close();
            }
            if (fos != null) {
                fos.close();
            }
        }
    }
}
