package us.pinguo.videoprocessor;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.hw.videoprocessor.VideoProcessor;
import com.hw.videoprocessor.util.CL;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class KTest {


    @Test
    public void testKichiku() throws Exception {
        CL.setLogEnable(true);
        Context context = InstrumentationRegistry.getTargetContext();
        File videoFile = new File("/mnt/sdcard/DCIM/test.mp4");
        File outVideoFile = new File("/mnt/sdcard/DCIM/test_k.mp4");
        outVideoFile.delete();
        File outVideoFile2 = new File("/mnt/sdcard/DCIM/test_k2.mp4");
        outVideoFile2.delete();
        File outVideoFile3 = new File("/mnt/sdcard/DCIM/test_k3.mp4");
        outVideoFile3.delete();
        File scale540File = new File("/mnt/sdcard/DCIM/test_scale.mp4");
        File scale720File = new File("/mnt/sdcard/DCIM/test_scale2.mp4");

        long s1 = System.currentTimeMillis();
        VideoProcessor.processVideo(context, videoFile.getAbsolutePath(), scale540File.getAbsolutePath(), 960, 540,
                null, null, null, 15000000, null, 0);
        long s2 = System.currentTimeMillis();
        VideoProcessor.processVideo(context, videoFile.getAbsolutePath(), scale720File.getAbsolutePath(), 1280, 720,
                null, null, null, 2500000, null, 1);
        long s3 = System.currentTimeMillis();
        VideoProcessor.reverseVideo(context, scale540File.getAbsolutePath(), outVideoFile.getAbsolutePath());
        VideoProcessor.processVideo(context, outVideoFile.getAbsolutePath(), outVideoFile3.getAbsolutePath(), null, null,
                null, null, null, 1500000, null, 1);
        long s4 = System.currentTimeMillis();
        VideoProcessor.reverseVideo(context, scale720File.getAbsolutePath(), outVideoFile2.getAbsolutePath());
        long s5 = System.currentTimeMillis();
        CL.e(String.format("doKichiku,scale:%dms,%dms,kichiku:%dms,%dms", s2 - s1, s3 - s2, s4 - s3, s5 - s4));
    }
}
