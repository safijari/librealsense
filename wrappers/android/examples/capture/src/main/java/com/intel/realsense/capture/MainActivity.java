package com.intel.realsense.capture;

import static org.opencv.core.CvType.CV_8UC1;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.intel.realsense.librealsense.Colorizer;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.DepthFrame;
import com.intel.realsense.librealsense.DepthSensor;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameCallback;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.GLRsSurfaceView;
import com.intel.realsense.librealsense.Option;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.os.Environment;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

public class MainActivity extends AppCompatActivity {
    static {
        if (!OpenCVLoader.initDebug())
            Log.d("ERROR", "Unable to load OpenCV");
        else
            Log.d("SUCCESS", "OpenCV loaded");
    }

    private static final String TAG = "librs capture example";
    private static final int PERMISSIONS_REQUEST_CAMERA = 0;
    private static final int PERMISSIONS_REQUEST_FILES = 1;

    private boolean mPermissionsGranted = false;

    private Context mAppContext;
    private TextView mBackGroundText;
    private GLRsSurfaceView mGLSurfaceView;
    private boolean mIsStreaming = false;
    private final Handler mHandler = new Handler();

    private Pipeline mPipeline;
    private Colorizer mColorizer;
    private RsContext mRsContext;

    private File mCaptureFolder = null;

    private Imgcodecs imageCodes = new Imgcodecs();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAppContext = getApplicationContext();
        mBackGroundText = findViewById(R.id.connectCameraText);
        mGLSurfaceView = findViewById(R.id.glSurfaceView);
        mGLSurfaceView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // Android 9 also requires camera permissions
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.O &&
                (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED))  {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_CAMERA);

            return;
        }

        mPermissionsGranted = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mGLSurfaceView.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSIONS_REQUEST_CAMERA);
            return;
        }
        mPermissionsGranted = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(mPermissionsGranted)
            init();
        else
            Log.e(TAG, "missing permissions");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(mRsContext != null)
            mRsContext.close();
        stop();
        mColorizer.close();
        mPipeline.close();
    }

    private void init(){
        //RsContext.init must be called once in the application lifetime before any interaction with physical RealSense devices.
        //For multi activities applications use the application context instead of the activity context
        RsContext.init(mAppContext);

        //Register to notifications regarding RealSense devices attach/detach events via the DeviceListener.
        mRsContext = new RsContext();
        mRsContext.setDevicesChangedCallback(mListener);

        mPipeline = new Pipeline();
        mColorizer = new Colorizer();

        try(DeviceList dl = mRsContext.queryDevices()){

            if(dl.getDeviceCount() > 0) {
                showConnectLabel(false);
                start();
            }
        }
    }

    private void showConnectLabel(final boolean state){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBackGroundText.setVisibility(state ? View.VISIBLE : View.GONE);
            }
        });
    }

    private DeviceListener mListener = new DeviceListener() {
        @Override
        public void onDeviceAttach() {
            showConnectLabel(false);
        }

        @Override
        public void onDeviceDetach() {
            showConnectLabel(true);
            stop();
        }
    };

    private void saveFramesAsPNG(FrameSet frames) {
        final List<Frame> Irframes = new ArrayList<>();
        if (mCaptureFolder == null) {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            mCaptureFolder = new File(downloads + "/realsense_captures/" + System.currentTimeMillis());
            if (!mCaptureFolder.exists()) {
                mCaptureFolder.mkdirs();
            }
        }

        frames.foreach(new FrameCallback() {
            @Override
            public void onFrame(Frame f) {
                Irframes.add(f);
            }
        });

        if (Irframes.size() == 2)
        {
            VideoFrame frameL = Irframes.get(0).as(Extension.VIDEO_FRAME);
            VideoFrame frameR = Irframes.get(0).as(Extension.VIDEO_FRAME);
            Mat mrgbL  = new Mat(frameL.getHeight(), frameR.getWidth(), CV_8UC1);
            Mat mrgbR  = new Mat(frameR.getHeight(), frameR.getWidth(), CV_8UC1);

            byte[] dataL = new byte[frameL.getDataSize()];
            frameL.getData(dataL);
            mrgbL.put(0, 0, dataL);

            byte[] dataR = new byte[frameR.getDataSize()];
            frameL.getData(dataR);
            mrgbR.put(0, 0, dataR);

            Mat out = new Mat(frameL.getHeight(), frameL.getWidth()*2, CV_8UC1);
            List<Mat> matlist = new ArrayList<>();
            matlist.add(mrgbL);
            matlist.add(mrgbR);

            Core.hconcat(matlist, out);
            File file = new File(mCaptureFolder, "frame_" + System.currentTimeMillis() + ".png");
            imageCodes.imwrite(file.toString(), out);
        }
    }

    Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            try {
                try(FrameSet frames = mPipeline.waitForFrames()) {
                    try(FrameSet processed = frames.applyFilter(mColorizer)) {
                        mGLSurfaceView.upload(processed);
                    }
                    saveFramesAsPNG(frames);
                }
                mHandler.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "streaming, error: " + e.getMessage());
            }
        }
    };


    private void configAndStart() throws Exception {
        try(Config config  = new Config())
        {
            config.enableStream(StreamType.INFRARED, 1, 640, 480, StreamFormat.Y8, 30);
            config.enableStream(StreamType.INFRARED, 2, 640, 480, StreamFormat.Y8, 30);
            // try statement needed here to release resources allocated by the Pipeline:start() method
            try(PipelineProfile pp = mPipeline.start(config)){
                pp.getDevice().querySensors().get(0).setValue(Option.EMITTER_ENABLED, 0f);
            }
        }
    }

    private synchronized void start() {
        if(mIsStreaming)
            return;
        try{
            Log.d(TAG, "try start streaming");
            mGLSurfaceView.clear();
            configAndStart();
            mIsStreaming = true;
            mHandler.post(mStreaming);
            Log.d(TAG, "streaming started successfully");
        } catch (Exception e) {
            Log.d(TAG, "failed to start streaming");
        }
    }

    private synchronized void stop() {
        mCaptureFolder = null;
        if(!mIsStreaming)
            return;
        try {
            Log.d(TAG, "try stop streaming");
            mIsStreaming = false;
            mHandler.removeCallbacks(mStreaming);
            mPipeline.stop();
            mGLSurfaceView.clear();
            Log.d(TAG, "streaming stopped successfully");
        } catch (Exception e) {
            Log.d(TAG, "failed to stop streaming");
        }
    }
}
