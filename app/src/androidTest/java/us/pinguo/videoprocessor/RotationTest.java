package us.pinguo.videoprocessor;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
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
public class RotationTest {

    //    @Test
    public void testRotation() throws Exception {
//        CL.setLogEnable(true);
//        Context context = InstrumentationRegistry.getTargetContext();
//        File videoFile = new File("/mnt/sdcard/DCIM/rotate.mp4");
//        File outFile = new File(context.getCacheDir(), "t.mp4");
//
//        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
//        retriever.setDataSource(videoFile.getAbsolutePath());
//        int rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
//        System.out.println("VideoRotation:" + rotation);
//        VideoProcessor.processVideo(context, videoFile.getAbsolutePath(), outFile.getAbsolutePath(), null, null, null, null, 2f,
//                1000000, null, null, new VideoProgressListener() {
//                    @Override
//                    public void onProgress(float progress) {
//                        Log.e("hwLog", "progress:" + progress);
//                    }
//                });
//        retriever.setDataSource(outFile.getAbsolutePath());
//        rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
//        System.out.println("processed VideoRotation:" + rotation);

    }

    @Test
    public void testReverse() throws Exception {
        CL.setLogEnable(true);
        Context context = InstrumentationRegistry.getTargetContext();
        File videoFile = new File("/mnt/sdcard/DCIM/rotate.mp4");
        File outFile = new File(context.getCacheDir(), "t.mp4");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(videoFile.getAbsolutePath());
        int rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        System.out.println("VideoRotation:" + rotation);
        VideoProcessor.reverseVideo(context, videoFile.getAbsolutePath(), outFile.getAbsolutePath(),true, progress ->
                Log.e("hwLog", "progress:" + progress)
        );
        retriever.setDataSource(outFile.getAbsolutePath());
        rotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        System.out.println("processed VideoRotation:" + rotation);

    }

}
