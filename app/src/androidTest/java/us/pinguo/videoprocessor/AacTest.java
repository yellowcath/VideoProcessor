package us.pinguo.videoprocessor;

import android.support.test.runner.AndroidJUnit4;
import com.hw.videoprocessor.util.AudioUtil;
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
public class AacTest {
    @Test
    public void test() throws Exception {
        File pcmFile = new File("/mnt/sdcard/t1.pcm");
        AudioUtil.copyFile(pcmFile.getAbsolutePath(),"/mnt/sdcard/t2.pcm");
        File filed = checkAndFillPcm(3564,new File("/mnt/sdcard/t2.pcm"),10000);
        AudioUtil.copyFile(filed.getAbsolutePath(),"/mnt/sdcard/filed.pcm");

    }

    public static File checkAndFillPcm(int aacDuration, File aacPcmFile, int videoDuration) {
        if (aacDuration >= videoDuration) {
            return aacPcmFile;
        }
        File cacheFile = new File(aacPcmFile.getAbsolutePath() + ".concat");
        FileInputStream is = null;
        FileOutputStream os = null;
        FileChannel from = null;
        FileChannel to = null;
        try {
            //计算填充次数
            float repeat = videoDuration / (float) aacDuration;
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

}
