package com.hw.videoprocessor.demo;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import com.hw.videoprocessor.VideoEffects;
import com.hw.videoprocessor.VideoProcessor;
import com.hw.videoprocessor.util.CL;
import com.jaygoo.widget.RangeSeekBar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {


    private static final int REQUEST_TAKE_GALLERY_VIDEO = 100;
    private VideoView videoView;
    private RangeSeekBar rangeSeekBar;
    private Runnable r;
    private ProgressDialog progressDialog;
    private Uri selectedVideoUri;
    private static final String TAG = "VideoProcessor";
    private static final String POSITION = "position";
    private static final String FILEPATH = "filepath";
    private int stopPosition;
    private TextView tvLeft, tvRight;
    private String filePath;
    private int duration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initActionBar();
        CL.setLogEnable(true);
        final TextView uploadVideo = (TextView) findViewById(R.id.uploadVideo);
        TextView cutVideo = (TextView) findViewById(R.id.cropVideo);
        TextView scaleVideo = (TextView) findViewById(R.id.scaleVideo);
        TextView mixAudio = (TextView) findViewById(R.id.mixAudio);
        TextView increaseSpeed = (TextView) findViewById(R.id.increaseSpeed);
        TextView decreaseSpeed = (TextView) findViewById(R.id.decreaseSpeed);
        final TextView reverseVideo = (TextView) findViewById(R.id.reverseVideo);


        tvLeft = (TextView) findViewById(R.id.tvLeft);
        tvRight = (TextView) findViewById(R.id.tvRight);

        videoView = (VideoView) findViewById(R.id.videoView);
        rangeSeekBar = (RangeSeekBar) findViewById(R.id.rangeSeekBar);
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle(null);
        progressDialog.setCancelable(false);
        progressDialog.setMessage("please wait......");
        rangeSeekBar.setEnabled(false);

        uploadVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    getPermission();
                } else {
                    uploadVideo();
                }

            }
        });

        cutVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeCutVideo((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000));
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });

        scaleVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeScaleVideo((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000));
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });

        mixAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeMixAudio((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000));
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });

        increaseSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeSpeedVideo((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000),
                            2f);
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });
        decreaseSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeSpeedVideo((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000),
                            0.5f);
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });

        reverseVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedVideoUri != null) {
                    executeRevertVideo((int) (rangeSeekBar.getCurrentRange()[0] * 1000), (int) (rangeSeekBar.getCurrentRange()[1] * 1000));
                } else {
                    Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void initActionBar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.activity_main_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle("VideoProcessor");
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_kichiku:
                        if (selectedVideoUri != null) {
                            executeKichikuVideo();
                        } else {
                            Toast.makeText(getApplicationContext(), "Please upload a video", Toast.LENGTH_SHORT).show();
                        }
                        break;

                }
                return true;
            }
        });

    }


    private void getPermission() {
        String[] params = null;
        String writeExternalStorage = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        String readExternalStorage = Manifest.permission.READ_EXTERNAL_STORAGE;

        int hasWriteExternalStoragePermission = ActivityCompat.checkSelfPermission(this, writeExternalStorage);
        int hasReadExternalStoragePermission = ActivityCompat.checkSelfPermission(this, readExternalStorage);
        List<String> permissions = new ArrayList<String>();

        if (hasWriteExternalStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(writeExternalStorage);
        }
        if (hasReadExternalStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(readExternalStorage);
        }

        if (!permissions.isEmpty()) {
            params = permissions.toArray(new String[permissions.size()]);
        }
        if (params != null && params.length > 0) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    params,
                    100);
        } else {
            uploadVideo();
        }
    }

    /**
     * Handling response for permission request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 100: {

                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    uploadVideo();
                }
            }
            break;
        }
    }

    /**
     * Opening gallery for uploading video
     */
    private void uploadVideo() {
        try {
            Intent intent = new Intent();
            intent.setType("video/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select Video"), REQUEST_TAKE_GALLERY_VIDEO);
        } catch (Exception e) {

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPosition = videoView.getCurrentPosition(); //stopPosition is an int
        videoView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoView.seekTo(stopPosition);
        videoView.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_TAKE_GALLERY_VIDEO) {
                selectedVideoUri = data.getData();
                videoView.setVideoURI(selectedVideoUri);
                videoView.start();


                videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {

                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        duration = mp.getDuration() / 1000;
                        tvLeft.setText("00:00:00");

                        tvRight.setText(getTime(mp.getDuration() / 1000));
                        mp.setLooping(true);
                        rangeSeekBar.setRange(0, duration);
                        rangeSeekBar.setValue(0, duration);
                        rangeSeekBar.setEnabled(true);
                        rangeSeekBar.requestLayout();

                        rangeSeekBar.setOnRangeChangedListener(new RangeSeekBar.OnRangeChangedListener() {
                            @Override
                            public void onRangeChanged(RangeSeekBar view, float min, float max, boolean isFromUser) {
                                videoView.seekTo((int) min * 1000);

                                tvLeft.setText(getTime((int) view.getCurrentRange()[0]));

                                tvRight.setText(getTime((int) view.getCurrentRange()[1]));
                            }
                        });

                        final Handler handler = new Handler();
                        handler.postDelayed(r = new Runnable() {
                            @Override
                            public void run() {
                                if (videoView.getCurrentPosition() >= rangeSeekBar.getCurrentRange()[1] * 1000) {
                                    videoView.seekTo((int) rangeSeekBar.getCurrentRange()[0] * 1000);
                                }
                                handler.postDelayed(r, 1000);
                            }
                        }, 1000);

                    }
                });

//                }
            }
        }
    }

    private String getTime(int seconds) {
        int hr = seconds / 3600;
        int rem = seconds % 3600;
        int mn = rem / 60;
        int sec = rem % 60;
        return String.format("%02d", hr) + ":" + String.format("%02d", mn) + ":" + String.format("%02d", sec);
    }

    private void executeCutVideo(final int startMs, final int endMs) {
        progressDialog.show();
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );

        String filePrefix = "cut_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(selectVideoPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoProcessor.cutVideo(getApplicationContext(), selectVideoPath, filePath, startMs, endMs);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    private void executeScaleVideo(final int startMs, final int endMs) {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );
        progressDialog.show();
        String filePrefix = "scale_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(selectVideoPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(selectVideoPath);
                    int originWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int originHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    int bitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));

                    int outWidth = originWidth / 2;
                    int outHeight = originHeight / 2;
                    VideoProcessor.processVideo(getApplicationContext(), selectVideoPath, filePath,
                            outWidth, outHeight, startMs, endMs, null, bitrate / 2, null);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    private void executeMixAudio(final int startMs, final int endMs) {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );
        progressDialog.show();
        String filePrefix = "scale_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String aacPath = new File(getCacheDir(), "test.aac").getAbsolutePath();
                    copyAssets("test.aac", aacPath);
                    VideoProcessor.mixAudioTrack(getApplicationContext(), selectVideoPath, aacPath, filePath, startMs, endMs, 50, 50);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    private void executeKichikuVideo() {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );
        progressDialog.show();
        String filePrefix = "kichiku_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(selectVideoPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoEffects.doKichiku(getApplicationContext(), selectVideoPath, filePath, 2, 2000);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    private void executeSpeedVideo(final int startMs, final int endMs, final float speed) {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );
        progressDialog.show();
        String filePrefix = "speed_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(selectVideoPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoProcessor.processVideo(getApplicationContext(), selectVideoPath, filePath,
                            null, null, startMs, endMs, speed, null, null);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    private void executeRevertVideo(final int startMs, final int endMs) {
        File moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
        );
        progressDialog.show();
        String filePrefix = "revert_video";
        String fileExtn = ".mp4";
        final String selectVideoPath = getPath(MainActivity.this, selectedVideoUri);
        File dest = new File(moviesDir, filePrefix + fileExtn);
        int fileNo = 0;
        while (dest.exists()) {
            fileNo++;
            dest = new File(moviesDir, filePrefix + fileNo + fileExtn);
        }
        filePath = dest.getAbsolutePath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(selectVideoPath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoProcessor.revertVideo(getApplicationContext(), selectVideoPath, filePath);
                    Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                    intent.putExtra(FILEPATH, filePath);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    postError();
                }
                progressDialog.dismiss();
            }
        }).start();
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     */
    private String getPath(final Context context, final Uri uri) {

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    /**
     * Get the value of the data column for this Uri.
     */
    private String getDataColumn(Context context, Uri uri, String selection,
                                 String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void postError() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "process error!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    private void copyAssets(String assetsName, String path) throws IOException {
        AssetFileDescriptor assetFileDescriptor = getAssets().openFd(assetsName);
        FileChannel from = new FileInputStream(assetFileDescriptor.getFileDescriptor()).getChannel();
        FileChannel to = new FileOutputStream(path).getChannel();
        from.transferTo(assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength(), to);
    }
}
