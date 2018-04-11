package us.pinguo.videoprocessor;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.hw.videoprocessor.VideoProcessor;
import com.hw.videoprocessor.util.AudioFadeUtil;
import com.hw.videoprocessor.util.AudioUtil;
import com.hw.videoprocessor.util.CL;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class PcmTest {

    @Test
    public void testReverse() throws Exception {
        CL.setLogEnable(true);
        Context context = InstrumentationRegistry.getTargetContext();
        File videoFile = new File("/sdcard/DCIM/Camera/VID_20180411_152833.mp4");
        File outFile = new File("/sdcard/re.mp4");
        File pcmFile = new File("/sdcard/re.pcm");
        File pcmFile2 = new File("/sdcard/re2.pcm");
        File pcmFile3 = new File("/sdcard/re3.pcm");


        VideoProcessor.revertVideo(context, videoFile.getAbsolutePath(), outFile.getAbsolutePath());
        AudioUtil.decodeToPCM(outFile.getAbsolutePath(), pcmFile3.getAbsolutePath(), null, null);
        AudioUtil.decodeToPCM(videoFile.getAbsolutePath(), pcmFile.getAbsolutePath(), null, null);
        AudioUtil.reversePcm(pcmFile.getAbsolutePath(), pcmFile2.getAbsolutePath());
    }

    public void testFade() throws Exception {
        CL.setLogEnable(true);
        Context context = InstrumentationRegistry.getTargetContext();
        File aacFile = new File(context.getCacheDir(), "test.aac");
        copyAssets(context, "test.aac", aacFile.getAbsolutePath());
        File cacheDir = new File("/mnt/sdcard/test");
        File pcmFile = new File(cacheDir, "t.pcm");
        long s = System.currentTimeMillis();
        AudioUtil.decodeToPCM(aacFile.getAbsolutePath(), pcmFile.getAbsolutePath(), null, null);
        long e1 = System.currentTimeMillis();
        AudioFadeUtil.audioFade(pcmFile.getAbsolutePath(), 44100, 2, 1, 1);
        long e2 = System.currentTimeMillis();
        CL.e("decodeToPCM:" + (e1 - s) + "ms" + " audioFade:" + (e2 - e1) + "ms");
    }

    private void copyAssets(Context context, String assetsName, String path) throws IOException {
        AssetFileDescriptor assetFileDescriptor = context.getAssets().openFd(assetsName);
        FileChannel from = new FileInputStream(assetFileDescriptor.getFileDescriptor()).getChannel();
        FileChannel to = new FileOutputStream(path).getChannel();
        from.transferTo(assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength(), to);
    }
}
