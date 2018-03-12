package com.hw.videoprocessor.util;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.support.annotation.IntRange;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Created by huangwei on 2018/3/7 0007.
 */

public class AudioUtil {
    public static void adjustPcmVolumn(String fromPath, String toPath, @IntRange(from = 0, to = 100) int volumn) throws IOException {
        float vol = normalizeVolumn(volumn);

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
     * @param volumn
     * @return 0~50 -> 0~1
     * 50~100 ->1~40
     */
    private static float normalizeVolumn(@IntRange(from = 0, to = 100) int volumn) {
        if (volumn <= 50) {
            return volumn / 50f;
        } else {
            return (volumn - 50) / 50f * 39 + 1;
        }
    }

    public static void mixPcm(String pcm1Path, String pcm2Path, String toPath
            , @IntRange(from = 0, to = 100) int volumn1
            , @IntRange(from = 0, to = 100) int volumn2) throws IOException {
        float vol1 = normalizeVolumn(volumn1);
        float vol2 = normalizeVolumn(volumn2);

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
        FileInputStream is = new FileInputStream(from);
        FileOutputStream os = new FileOutputStream(to);
        byte[] buffer1 = new byte[2048];
        byte[] buffer2 = new byte[1024];

        while (is.read(buffer1) != -1) {
            for (int i = 0; i < buffer2.length; i += 2) {
                buffer2[i] = buffer1[2 * i];
                buffer2[i + 1] = buffer1[2 * i + 1];
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
}
