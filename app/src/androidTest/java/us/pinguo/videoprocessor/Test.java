package us.pinguo.videoprocessor;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;
import com.hw.videoprocessor.VideoUtil;
import com.hw.videoprocessor.util.CL;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class Test {

    @org.junit.Test
    public void test() throws Exception {
        CL.setLogEnable(true);
        Context context = InstrumentationRegistry.getTargetContext();
        File dyFile = new File("/sdcard/dy.mp4");
        File cFile = new File("/sdcard/c.mp4");

        Pair<Integer, Integer> videoFrameCount = VideoUtil.getVideoFrameCount(dyFile.getAbsolutePath());
        Pair<Integer, Integer> videoFrameCount1 = VideoUtil.getVideoFrameCount(cFile.getAbsolutePath());
        CL.setLogEnable(false);
    }
}
