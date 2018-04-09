package us.pinguo.videoprocessor;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.hw.videoprocessor.VideoProcessor;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class RotationTest {

    @Test
    public void testRotation() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File videoFile = new File("/mnt/sdcard/DCIM/rotate.mp4");
        File outFile = new File(context.getCacheDir(),"t.mp4");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(videoFile.getAbsolutePath());
        int rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        System.out.println("VideoRotation:"+rotation);
        VideoProcessor.processVideo(context,videoFile.getAbsolutePath(),outFile.getAbsolutePath(),null,null,null,null,null,
                1000000,null);
        retriever.setDataSource(outFile.getAbsolutePath());
        rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        System.out.println("processed VideoRotation:"+rotation);

    }

}
