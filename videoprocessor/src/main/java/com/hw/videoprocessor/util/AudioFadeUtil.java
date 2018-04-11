package com.hw.videoprocessor.util;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Created by huangwei on 2018/4/11 0011.
 */

public class AudioFadeUtil {
    /**
     * 只支持16bit pcm
     */
    public static void audioFade(String pcmPath, int sampleRate, int channelCount, float fadeInSec, float fadeOutSec) throws IOException {
        int bit = 16;
        int startLen = (int) (sampleRate * bit / 8 * channelCount * fadeInSec);
        int endLen = (int) (sampleRate * bit / 8 * channelCount * fadeOutSec);

        byte startArray[] = new byte[startLen];
        byte endArray[] = new byte[endLen];


        RandomAccessFile raf = new RandomAccessFile(pcmPath, "rw");
        raf.read(startArray, 0, startLen);
        raf.seek((int) (raf.length() - endLen));
        raf.read(endArray, 0, endLen);

        doFaceIn(startArray);
        doFaceOut(endArray);

        raf.seek(0);
        raf.write(startArray, 0, startLen);
        raf.seek((int) (raf.length() - endLen));
        raf.write(endArray, 0, endLen);
        raf.close();
    }

    private static void doFaceIn(byte[] bytes) {
        float step = 1 / (bytes.length / 2f);
        float volume = 0;
        int tmp;
        for (int i = 0; i < bytes.length; i += 2) {
            tmp = (short) ((bytes[i] & 0xff) | (bytes[i + 1] & 0xff) << 8);
            tmp *= volume;
            if (tmp > 32767) {
                tmp = 32767;
            } else if (tmp < -32768) {
                tmp = -32768;
            }
            bytes[i] = (byte) (tmp & 0xFF);
            bytes[i + 1] = (byte) ((tmp >>> 8) & 0xFF);
            volume += step;
        }
    }

    private static void doFaceOut(byte[] bytes) {
        float step = 1 / (bytes.length / 2f);
        float volume = 1;
        int tmp;
        for (int i = 0; i < bytes.length; i += 2) {
            tmp = (short) ((bytes[i] & 0xff) | (bytes[i + 1] & 0xff) << 8);
            tmp *= volume;
            if (tmp > 32767) {
                tmp = 32767;
            } else if (tmp < -32768) {
                tmp = -32768;
            }
            bytes[i] = (byte) (tmp & 0xFF);
            bytes[i + 1] = (byte) ((tmp >>> 8) & 0xFF);
            volume -= step;
        }
    }
}
