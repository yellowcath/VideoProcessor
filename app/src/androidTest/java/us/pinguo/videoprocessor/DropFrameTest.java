package us.pinguo.videoprocessor;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import com.hw.videoprocessor.VideoUtil;
import com.hw.videoprocessor.util.CL;
import com.hw.videoprocessor.util.FrameDropper;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class DropFrameTest {

    @org.junit.Test
    public void test() throws Exception {
        CL.setLogEnable(true);
        doTest(600,60,24);
    }

    private void doTest(int totalFrame,int srcFrameRate,int targetFrameRate){
        FrameDropper dropper = new FrameDropper(srcFrameRate,targetFrameRate);
        CL.i("totalFrame:"+totalFrame+",srcFrameRate:"+srcFrameRate+",targetFrameRate:"+targetFrameRate);
        for(int i=0;i<totalFrame;i++){
            boolean drop = dropper.checkDrop(i);
            CL.i("第"+i+"帧,drop:"+drop);
        }
        dropper.printResult();
    }
}
