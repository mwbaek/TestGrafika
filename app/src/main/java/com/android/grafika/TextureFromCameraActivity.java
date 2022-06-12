/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.app.Activity;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;
import com.zoomda.composable.VideoRenderer;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Direct the Camera preview to a GLES texture and manipulate it.
 * <p>
 * We manage the Camera and GLES rendering from a dedicated thread.  We don't animate anything,
 * so we don't need a Choreographer heartbeat -- just redraw when we get a new frame from the
 * camera or the user has caused a change in size or position.
 * <p>
 * The Camera needs to follow the activity pause/resume cycle so we don't keep it locked
 * while we're in the background.  Also, for power reasons, we don't want to keep getting
 * frames when the screen is off.  As noted in
 * http://source.android.com/devices/graphics/architecture.html#activity
 * the Surface lifecycle isn't quite the same as the activity's.  We follow approach #1.
 * <p>
 * The tricky part about the lifecycle is that our SurfaceView's Surface can outlive the
 * Activity, and we can get surface callbacks while paused, so we need to keep track of it
 * in a static variable and be prepared for calls at odd times.
 * <p>
 * The zoom, size, and rotate values are determined by the values stored in the "seek bars"
 * (sliders).  When the device is rotated, the Activity is paused and resumed, but the
 * controls retain their value, which is kind of nice.  The position, set by touch, is lost
 * on rotation.
 * <p>
 * The UI updates go through a multi-stage process:
 * <ol>
 * <li> The user updates a slider.
 * <li> The new value is passed as a percent to the render thread.
 * <li> The render thread converts the percent to something concrete (e.g. size in pixels).
 *      The rect geometry is updated.
 * <li> (For most things) The values computed by the render thread are sent back to the main
 *      UI thread.
 * <li> (For most things) The UI thread updates some text views.
 * </ol>
 */
public class TextureFromCameraActivity extends Activity implements SurfaceHolder.Callback,
        SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "TestGrafika";

    private static final int DEFAULT_ZOOM_PERCENT = 0;      // 0-100
    private static final int DEFAULT_SIZE_PERCENT = 50;     // 0-100
    private static final int DEFAULT_ROTATE_PERCENT = 0;    // 0-100

    // Requested values; actual may differ.
    private static final int REQ_CAMERA_WIDTH = 1280;
    private static final int REQ_CAMERA_HEIGHT = 720;
    private static final int REQ_CAMERA_FPS = 30;

    // The holder for our SurfaceView.  The Surface can outlive the Activity (e.g. when
    // the screen is turned off and back on with the power button).
    //
    // This becomes non-null after the surfaceCreated() callback is called, and gets set
    // to null when surfaceDestroyed() is called.
    private static SurfaceHolder sSurfaceHolder;

    // Thread that handles rendering and controls the camera.  Started in onResume(),
    // stopped in onPause().
    private VideoRenderer mVideoRenderer = new VideoRenderer();

    // Receives messages from renderer thread.
    private MainHandler mHandler;

    // User controls.
    private SeekBar mZoomBar;
    private SeekBar mSizeBar;
    private SeekBar mRotateBar;

    // These values are passed to us by the camera/render thread, and displayed in the UI.
    // We could also just peek at the values in the RenderThread object, but we'd need to
    // synchronize access carefully.
    private int mCameraPreviewWidth, mCameraPreviewHeight;
    private float mCameraPreviewFps;
    private int mRectWidth, mRectHeight;
    private int mZoomWidth, mZoomHeight;
    private int mRotateDeg;
    private boolean mRecordingEnabled;      // controls button state

    private static TextureMovieEncoder mVideoEncoder = new TextureMovieEncoder();

    private static boolean mIsOverlayTask = true;

    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
    public static final File getCaptureFile(final String type, final String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(type), "Grafica");
        Log.d(TAG, "path=" + dir.toString());
        dir.mkdirs();
        if (dir.canWrite()) {
            return new File(dir, getDateTimeString() + ext);
        }
        return null;
    }

    private static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return mDateTimeFormat.format(now.getTime());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_from_camera);

        mHandler = new MainHandler(this);

        SurfaceView sv = (SurfaceView) findViewById(R.id.cameraOnTexture_surfaceView);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        mZoomBar = (SeekBar) findViewById(R.id.tfcZoom_seekbar);
        mSizeBar = (SeekBar) findViewById(R.id.tfcSize_seekbar);
        mRotateBar = (SeekBar) findViewById(R.id.tfcRotate_seekbar);
        mZoomBar.setProgress(DEFAULT_ZOOM_PERCENT);
        mSizeBar.setProgress(DEFAULT_SIZE_PERCENT);
        mRotateBar.setProgress(DEFAULT_ROTATE_PERCENT);
        mZoomBar.setOnSeekBarChangeListener(this);
        mSizeBar.setOnSeekBarChangeListener(this);
        mRotateBar.setOnSeekBarChangeListener(this);

        updateControls();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume BEGIN");
        super.onResume();

        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this, false);
            return;
        }

        mVideoRenderer.start(sSurfaceHolder);

        Log.d(TAG, "onResume END");
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause BEGIN");
        super.onPause();

        mVideoRenderer.stop();
        Log.d(TAG, "onPause END");
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder + " (static=" + sSurfaceHolder + ")");
        if (sSurfaceHolder != null) {
            throw new RuntimeException("sSurfaceHolder is already set");
        }

        sSurfaceHolder = holder;

        mVideoRenderer.onSurfaceCreated(holder);
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        mVideoRenderer.onSurfaceChanged(format, width, height);
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        // In theory we should tell the RenderThread that the surface has been destroyed.
        mVideoRenderer.onSurfaceDestroyed(holder);
        sSurfaceHolder = null;
    }

    @Override   // SeekBar.OnSeekBarChangeListener
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        // "progress" ranges from 0 to 100
        if (seekBar == mZoomBar) {
            mVideoRenderer.onProgressChangedZoom(progress);
        } else if (seekBar == mSizeBar) {
            mVideoRenderer.onProgressChangedSize(progress);
        } else if (seekBar == mRotateBar) {
            mVideoRenderer.onProgressChangedRotate(progress);
        } else {
            throw new RuntimeException("unknown seek bar");
        }
    }

    @Override   // SeekBar.OnSeekBarChangeListener
    public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override   // SeekBar.OnSeekBarChangeListener
    public void onStopTrackingTouch(SeekBar seekBar) {}
    @Override

    /**
     * Handles any touch events that aren't grabbed by one of the controls.
     */
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_DOWN:
                //Log.v(TAG, "onTouchEvent act=" + e.getAction() + " x=" + x + " y=" + y);
                mVideoRenderer.setPosition(x, y);
                break;
            default:
                break;
        }

        return true;
    }

    /**
     * Updates the current state of the controls.
     */
    private void updateControls() {
        String str = getString(R.string.tfcCameraParams, mCameraPreviewWidth,
                mCameraPreviewHeight, mCameraPreviewFps);
        TextView tv = (TextView) findViewById(R.id.tfcCameraParams_text);
        tv.setText(str);

        str = getString(R.string.tfcRectSize, mRectWidth, mRectHeight);
        tv = (TextView) findViewById(R.id.tfcRectSize_text);
        tv.setText(str);

        str = getString(R.string.tfcZoomArea, mZoomWidth, mZoomHeight);
        tv = (TextView) findViewById(R.id.tfcZoomArea_text);
        tv.setText(str);

        Button toggleRelease = (Button) findViewById(R.id.toggleRecording_button);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);
    }

    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        updateControls();

        if(mRecordingEnabled){
            File outputFile = new File(getFilesDir(), "camera-test.mp4");
            try {
                outputFile = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4");
            } catch (final NullPointerException e) {
                throw new RuntimeException("This app has no permission of writing external storage");
            }

            mVideoRenderer.startRecording(outputFile);
        }else{
            mVideoRenderer.stopRecording();
        }
    }

    int count = 0;
    public void clickAddImage(@SuppressWarnings("unused") View unused) {
        if(count == 0){
            Drawable drawable = getDrawable(R.drawable.auto_pilot_off);
            BitmapDrawable drawableBitmap = (BitmapDrawable) drawable;
            mVideoRenderer.addImage(drawableBitmap.getBitmap());
            count++;
        }
        else{
            Drawable drawable = getDrawable(R.drawable.ic_launcher);
            BitmapDrawable drawableBitmap = (BitmapDrawable) drawable;
            mVideoRenderer.addImage(drawableBitmap.getBitmap());
        }
    }

    /**
     * Custom message handler for main UI thread.
     * <p>
     * Receives messages from the renderer thread with UI-related updates, like the camera
     * parameters (which we show in a text message on screen).
     */
    private static class MainHandler extends Handler {
        private static final int MSG_SEND_CAMERA_PARAMS0 = 0;
        private static final int MSG_SEND_CAMERA_PARAMS1 = 1;
        private static final int MSG_SEND_RECT_SIZE = 2;
        private static final int MSG_SEND_ZOOM_AREA = 3;
        private static final int MSG_SEND_ROTATE_DEG = 4;

        private WeakReference<TextureFromCameraActivity> mWeakActivity;

        public MainHandler(TextureFromCameraActivity activity) {
            mWeakActivity = new WeakReference<TextureFromCameraActivity>(activity);
        }

        /**
         * Sends the updated camera parameters to the main thread.
         * <p>
         * Call from render thread.
         */
        public void sendCameraParams(int width, int height, float fps) {
            // The right way to do this is to bundle them up into an object.  The lazy
            // way is to send two messages.
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS0, width, height));
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS1, (int) (fps * 1000), 0));
        }

        /**
         * Sends the updated rect size to the main thread.
         * <p>
         * Call from render thread.
         */
        public void sendRectSize(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_RECT_SIZE, width, height));
        }

        /**
         * Sends the updated zoom area to the main thread.
         * <p>
         * Call from render thread.
         */
        public void sendZoomArea(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_ZOOM_AREA, width, height));
        }

        /**
         * Sends the updated zoom area to the main thread.
         * <p>
         * Call from render thread.
         */
        public void sendRotateDeg(int rot) {
            sendMessage(obtainMessage(MSG_SEND_ROTATE_DEG, rot, 0));
        }

        @Override
        public void handleMessage(Message msg) {
            TextureFromCameraActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_SEND_CAMERA_PARAMS0: {
                    activity.mCameraPreviewWidth = msg.arg1;
                    activity.mCameraPreviewHeight = msg.arg2;
                    break;
                }
                case MSG_SEND_CAMERA_PARAMS1: {
                    activity.mCameraPreviewFps = msg.arg1 / 1000.0f;
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_RECT_SIZE: {
                    activity.mRectWidth = msg.arg1;
                    activity.mRectHeight = msg.arg2;
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_ZOOM_AREA: {
                    activity.mZoomWidth = msg.arg1;
                    activity.mZoomHeight = msg.arg2;
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_ROTATE_DEG: {
                    activity.mRotateDeg = msg.arg1;
                    activity.updateControls();
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }


//    /**
//     * Thread that handles all rendering and camera operations.
//     */
//    private static class RenderThread extends Thread implements
//            SurfaceTexture.OnFrameAvailableListener {
//        // Object must be created on render thread to get correct Looper, but is used from
//        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
//        // constructed object.
//        private volatile RenderHandler mHandler;
//
//        // Used to wait for the thread to start.
//        private Object mStartLock = new Object();
//        private boolean mReady = false;
//
//        private MainHandler mMainHandler;
//
//        private Camera mCamera;
//        private int mCameraPreviewWidth, mCameraPreviewHeight;
//
//        private EglCore mEglCore;
//
//        // Orthographic projection matrix.
//        private float[] mDisplayProjectionMatrix = new float[16];
//
//        // Receives the output from the camera preview.
//        //private VideoInput mCameraVideoInput = new VideoInput();
//        private ArrayList<VideoInput> mSourceList = new ArrayList<>();
//        private Bitmap mBitmap;
//
//        private int mZoomPercent = DEFAULT_ZOOM_PERCENT;
//        private int mSizePercent = DEFAULT_SIZE_PERCENT;
//        private int mRotatePercent = DEFAULT_ROTATE_PERCENT;
//        private float mPosX, mPosY;
//        private boolean mRecordingStatus = false;
//        private File mOutputFile;
//
//        private VideoOutput mViewerOutput = new VideoOutput();
//        private boolean finishSurfaceAvailable = false;
//        private boolean startFinishSurfaceSetup = false;
//        private boolean finishSurfaceChanged = false;
//
//        /**
//         * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
//         * Activity.
//         */
//        public RenderThread(MainHandler handler) {
//            mMainHandler = handler;
//        }
//
//        /**
//         * Thread entry point.
//         */
//        @Override
//        public void run() {
//            Looper.prepare();
//
//            // We need to create the Handler before reporting ready.
//            mHandler = new RenderHandler(this);
//            synchronized (mStartLock) {
//                mReady = true;
//                mStartLock.notify();    // signal waitUntilReady()
//            }
//
//            // Prepare EGL and open the camera before we start handling messages.
//            mEglCore = new EglCore(null, 0);
//            openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_CAMERA_FPS);
//
//            Looper.loop();
//
//            Log.d(TAG, "looper quit");
//            releaseCamera();
//            releaseGl();
//            mEglCore.release();
//
//            synchronized (mStartLock) {
//                mReady = false;
//            }
//        }
//
//        /**
//         * Waits until the render thread is ready to receive messages.
//         * <p>
//         * Call from the UI thread.
//         */
//        public void waitUntilReady() {
//            synchronized (mStartLock) {
//                while (!mReady) {
//                    try {
//                        mStartLock.wait();
//                    } catch (InterruptedException ie) { /* not expected */ }
//                }
//            }
//        }
//
//        /**
//         * Shuts everything down.
//         */
//        private void shutdown() {
//            Log.d(TAG, "shutdown");
//            Looper.myLooper().quit();
//        }
//
//        private void addImage(float scale){
//            VideoInput mImageVideoInput = new VideoInput();
//            mImageVideoInput.scale = scale;
//            mImageVideoInput.texProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
//            mImageVideoInput.textureId = mImageVideoInput.texProgram.createTextureObject();
//            Log.d(TAG, "mImageVideoInput.textureId: "+mImageVideoInput.textureId);
//            mImageVideoInput.surfaceTexture = new SurfaceTexture(mImageVideoInput.textureId);
//            mImageVideoInput.surfaceTexture.setDefaultBufferSize(640, 480);
//            mImageVideoInput.surface = new Surface(mImageVideoInput.surfaceTexture);
//
//            Canvas c;
//            if (Build.VERSION.SDK_INT >= 23) {
//                c = mImageVideoInput.surface.lockHardwareCanvas();
//            } else {
//                c = mImageVideoInput.surface.lockCanvas(null);
//            }
//            if (c != null) {
//                try
//                {
//                    c.drawColor( 0, PorterDuff.Mode.CLEAR );
//                    Log.d(TAG, "mBitmap: "+this.mBitmap);
//                    c.drawBitmap(mBitmap, 0, 0, null);
//                } catch (Exception e){
//                    Log.d(TAG, e.getMessage());
//                }
//                mImageVideoInput.surface.unlockCanvasAndPost(c);
//            }
//
//            if(mIsOverlayTask)
//                mSourceList.add(mImageVideoInput);
//        }
//
//        /**
//         * Returns the render thread's Handler.  This may be called from any thread.
//         */
//        public RenderHandler getHandler() {
//            return mHandler;
//        }
//
//        /**
//         * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
//         */
//        private void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
//            Surface surface = holder.getSurface();
//            mViewerOutput.mWindowSurface = new WindowSurface(mEglCore, surface, false);
//            mViewerOutput.mWindowSurface.makeCurrent();
//
//            // Create and configure the SurfaceTexture, which will receive frames from the
//            // camera.  We set the textured rect's program to render from it.
//
//            //addImage(0.5f);
//
//            VideoInput mCameraVideoInput = new VideoInput();
//            mCameraVideoInput.scale = 1.0f;
//            mCameraVideoInput.texProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
//            mCameraVideoInput.textureId = mCameraVideoInput.texProgram.createTextureObject();
//            Log.d(TAG, "mCameraVideoInput.textureId: "+mCameraVideoInput.textureId);
//            mCameraVideoInput.surfaceTexture = new SurfaceTexture(mCameraVideoInput.textureId);
//
//            if (!newSurface) {
//                // This Surface was established on a previous run, so no surfaceChanged()
//                // message is forthcoming.  Finish the surface setup now.
//                //
//                // We could also just call this unconditionally, and perhaps do an unnecessary
//                // bit of reallocating if a surface-changed message arrives.
//                mViewerOutput.mWindowSurfaceWidth = mViewerOutput.mWindowSurface.getWidth();
//                mViewerOutput.mWindowSurfaceHeight = mViewerOutput.mWindowSurface.getHeight();
//                finishSurfaceSetup();
//            }
//
//            mSourceList.add(mCameraVideoInput);
//
//            //
//            addImage(1.5f);
//
//
//            mSourceList.get(0).surfaceTexture.setOnFrameAvailableListener(this);
//            //mCameraVideoInput.surfaceTexture.setOnFrameAvailableListener(this);
//
//            finishSurfaceAvailable = true;
//            if(!startFinishSurfaceSetup && finishSurfaceChanged)
//                finishSurfaceSetup();
//        }
//
//        /**
//         * Releases most of the GL resources we currently hold (anything allocated by
//         * surfaceAvailable()).
//         * <p>
//         * Does not release EglCore.
//         */
//        private void releaseGl() {
//            GlUtil.checkGlError("releaseGl start");
//
//            if (mViewerOutput.mWindowSurface != null) {
//                mViewerOutput.mWindowSurface.release();
//                mViewerOutput.mWindowSurface = null;
//            }
//            for (int i=0; i<mSourceList.size(); i++){
//                VideoInput VideoInput = mSourceList.get(i);
//                if (VideoInput.surfaceTexture != null) {
//                    Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
//                    VideoInput.surfaceTexture.release();
//                    VideoInput.surfaceTexture = null;
//                }
//            }
//            GlUtil.checkGlError("releaseGl done");
//
//            mEglCore.makeNothingCurrent();
//        }
//
//        /**
//         * Handles the surfaceChanged message.
//         * <p>
//         * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
//         * could also be called with a Surface created on a previous run.  So this may not
//         * be called.
//         */
//        private void surfaceChanged(int width, int height) {
//            Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height);
//
//            mViewerOutput.mWindowSurfaceWidth = width;
//            mViewerOutput.mWindowSurfaceHeight = height;
//            if(finishSurfaceAvailable)
//                finishSurfaceSetup();
//            finishSurfaceChanged = true;
//        }
//
//        /**
//         * Handles the surfaceDestroyed message.
//         */
//        private void surfaceDestroyed() {
//            // In practice this never appears to be called -- the activity is always paused
//            // before the surface is destroyed.  In theory it could be called though.
//            Log.d(TAG, "RenderThread surfaceDestroyed");
//            releaseGl();
//        }
//
//        /**
//         * Sets up anything that depends on the window size.
//         * <p>
//         * Open the camera (to set mCameraAspectRatio) before calling here.
//         */
//        private synchronized void finishSurfaceSetup() {
//            startFinishSurfaceSetup = true;
//            int width = mViewerOutput.mWindowSurfaceWidth;
//            int height = mViewerOutput.mWindowSurfaceHeight;
//            Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
//                    " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);
//
//            // Use full window.
//            GLES20.glViewport(0, 0, width, height);
//
//            // Simple orthographic projection, with (0,0) in lower-left corner.
//            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);
//
//            // Default position is center of screen.
//            mPosX = width / 2.0f;
//            mPosY = height / 2.0f;
//
//            updateGeometry();
//
//            // Ready to go, start the camera.
//            Log.d(TAG, "starting camera preview");
//            try {
//                mCamera.setPreviewTexture(mSourceList.get(0).surfaceTexture);
//            } catch (IOException ioe) {
//                throw new RuntimeException(ioe);
//            }
//            try{
//                mCamera.startPreview();
//            }catch (Throwable t){}
//        }
//
//        /**
//         * Updates the geometry of mRect, based on the size of the window and the current
//         * values set by the UI.
//         */
//        private void updateGeometry() {
//            int width = mViewerOutput.mWindowSurfaceWidth;
//            int height = mViewerOutput.mWindowSurfaceHeight;
//
//            int smallDim = Math.min(width, height);
//            // Max scale is a bit larger than the screen, so we can show over-size.
//            float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
//            float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
//            int newWidth = Math.round(scaled * cameraAspect);
//            int newHeight = Math.round(scaled);
//
//            float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
//            int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));
//
//            mViewerOutput.mRect.setScale(newWidth, newHeight);
//            mViewerOutput.mRect.setPosition(mPosX, mPosY);
//            mViewerOutput.mRect.setRotation(rotAngle);
//            mViewerOutput.mRectDrawable.setScale(zoomFactor);
//
//            mMainHandler.sendRectSize(newWidth, newHeight);
//            mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
//                    Math.round(mCameraPreviewHeight * zoomFactor));
//            mMainHandler.sendRotateDeg(rotAngle);
//        }
//
//        @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
//        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
//            mHandler.sendFrameAvailable();
//        }
//
//        /**
//         * Handles incoming frame of data from the camera.
//         */
//        private void frameAvailable() {
//            draw();
//        }
//
//        private void startRecording(File file){
//            mRecordingStatus = true;
//            this.mOutputFile = file;
//        }
//
//        private void stopRecording(){
//            mRecordingStatus = false;
//        }
//
//        /**
//         * Draws the scene and submits the buffer.
//         */
//        private void draw() {
//            GlUtil.checkGlError("draw start");
//
//            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
//            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//
//            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
//            GLES20.glEnable(GLES20.GL_BLEND);
//            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
//            GLES20.glDisable(GLES20.GL_CULL_FACE);
//
//            GLES20.glViewport(0, 0, mViewerOutput.mWindowSurfaceWidth, mViewerOutput.mWindowSurfaceHeight);
//
//            for (int i=0; i<mSourceList.size(); i++)
//            {
//                mSourceList.get(i).surfaceTexture.updateTexImage();
//                mViewerOutput.mRect.setTexture(mSourceList.get(i).textureId);
//
//                float scaleX = mViewerOutput.mRect.getScaleX();
//                float scaleY = mViewerOutput.mRect.getScaleY();
//                mViewerOutput.mRect.setScale(scaleX*mSourceList.get(i).scale, scaleY*mSourceList.get(i).scale);
//
//                mViewerOutput.mRect.setRotation(mSourceList.get(i).angle);
//
//                mViewerOutput.mRect.draw(mSourceList.get(i).texProgram, mDisplayProjectionMatrix);
//                mViewerOutput.mRect.setScale(scaleX, scaleY);
//            }
//            mViewerOutput.mWindowSurface.swapBuffers();
//
//            if(mVideoEncoder.isRecording()){
//                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
//                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//                for (int i=0; i<mSourceList.size(); i++)
//                {
//                    mVideoEncoder.setTextureId(mSourceList.get(i).textureId);
//                    mVideoEncoder.frameAvailable(mSourceList.get(i).surfaceTexture);
//                }
//            }
//
//            if(mRecordingStatus && !mVideoEncoder.isRecording()){
//                mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
//                        mOutputFile, 640, 360, 1000000, EGL14.eglGetCurrentContext()));
//            }else if(!mRecordingStatus && mVideoEncoder.isRecording()){
//                mVideoEncoder.stopRecording();
//            }
//
//            GlUtil.checkGlError("draw done");
//        }
//
//        private void setZoom(int percent) {
//            mZoomPercent = percent;
//            updateGeometry();
//        }
//
//        private void setSize(int percent) {
//            mSizePercent = percent;
//            updateGeometry();
//        }
//
//        private void setRotate(int percent) {
//            mRotatePercent = percent;
//            updateGeometry();
//        }
//
//        private void setPosition(int x, int y) {
//            mPosX = x;
//            mPosY = mViewerOutput.mWindowSurfaceHeight - y;   // GLES is upside-down
//            updateGeometry();
//        }
//
//        public void setBitmap(Bitmap mBitmap) {
//            this.mBitmap = mBitmap;
//            Log.d(TAG, "mBitmap: "+this.mBitmap);
//        }
//
//        /**
//         * Opens a camera, and attempts to establish preview mode at the specified width
//         * and height with a fixed frame rate.
//         * <p>
//         * Sets mCameraPreviewWidth / mCameraPreviewHeight.
//         */
//        private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
//            if (mCamera != null) {
//                throw new RuntimeException("camera already initialized");
//            }
//
//            Camera.CameraInfo info = new Camera.CameraInfo();
//
//            // Try to find a front-facing camera (e.g. for videoconferencing).
//            int numCameras = Camera.getNumberOfCameras();
//            for (int i = 0; i < numCameras; i++) {
//                Camera.getCameraInfo(i, info);
//                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
//                    mCamera = Camera.open(i);
//                    break;
//                }
//            }
//            if (mCamera == null) {
//                Log.d(TAG, "No front-facing camera found; opening default");
//                mCamera = Camera.open();    // opens first back-facing camera
//            }
//            if (mCamera == null) {
//                throw new RuntimeException("Unable to open camera");
//            }
//
//            Camera.Parameters parms = mCamera.getParameters();
//
//            CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);
//
//            // Try to set the frame rate to a constant value.
//            int thousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);
//
//            // Give the camera a hint that we're recording video.  This can have a big
//            // impact on frame rate.
//            parms.setRecordingHint(true);
//
//            mCamera.setParameters(parms);
//
//            int[] fpsRange = new int[2];
//            Camera.Size mCameraPreviewSize = parms.getPreviewSize();
//            parms.getPreviewFpsRange(fpsRange);
//            String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
//            if (fpsRange[0] == fpsRange[1]) {
//                previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
//            } else {
//                previewFacts += " @[" + (fpsRange[0] / 1000.0) +
//                        " - " + (fpsRange[1] / 1000.0) + "] fps";
//            }
//            Log.i(TAG, "Camera config: " + previewFacts);
//
//            mCameraPreviewWidth = mCameraPreviewSize.width;
//            mCameraPreviewHeight = mCameraPreviewSize.height;
//            mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight,
//                    thousandFps / 1000.0f);
//        }
//
//        /**
//         * Stops camera preview, and releases the camera to the system.
//         */
//        private void releaseCamera() {
//            if (mCamera != null) {
//                mCamera.stopPreview();
//                mCamera.release();
//                mCamera = null;
//                Log.d(TAG, "releaseCamera -- done");
//            }
//        }
//    }
//
//
//    /**
//     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
//     * <p>
//     * The object is created on the render thread, and the various "send" methods are called
//     * from the UI thread.
//     */
//    private static class RenderHandler extends Handler {
//        private static final int MSG_SURFACE_AVAILABLE = 0;
//        private static final int MSG_SURFACE_CHANGED = 1;
//        private static final int MSG_SURFACE_DESTROYED = 2;
//        private static final int MSG_SHUTDOWN = 3;
//        private static final int MSG_FRAME_AVAILABLE = 4;
//        private static final int MSG_ZOOM_VALUE = 5;
//        private static final int MSG_SIZE_VALUE = 6;
//        private static final int MSG_ROTATE_VALUE = 7;
//        private static final int MSG_POSITION = 8;
//        private static final int MSG_REDRAW = 9;
//        private static final int MSG_START_REC = 10;
//        private static final int MSG_STOP_REC = 11;
//
//        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
//        // but no real harm in it.
//        private WeakReference<RenderThread> mWeakRenderThread;
//
//        /**
//         * Call from render thread.
//         */
//        public RenderHandler(RenderThread rt) {
//            mWeakRenderThread = new WeakReference<RenderThread>(rt);
//        }
//
//        /**
//         * Sends the "surface available" message.  If the surface was newly created (i.e.
//         * this is called from surfaceCreated()), set newSurface to true.  If this is
//         * being called during Activity startup for a previously-existing surface, set
//         * newSurface to false.
//         * <p>
//         * The flag tells the caller whether or not it can expect a surfaceChanged() to
//         * arrive very soon.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendSurfaceAvailable(SurfaceHolder holder, boolean newSurface) {
//            Log.d(TAG, "sendSurfaceAvailable newSurface: "+newSurface);
//            sendMessage(obtainMessage(MSG_SURFACE_AVAILABLE,
//                    newSurface ? 1 : 0, 0, holder));
//        }
//
//        /**
//         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
//                int height) {
//            // ignore format
//            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
//        }
//
//        /**
//         * Sends the "shutdown" message, which tells the render thread to halt.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendSurfaceDestroyed() {
//            sendMessage(obtainMessage(MSG_SURFACE_DESTROYED));
//        }
//
//        /**
//         * Sends the "shutdown" message, which tells the render thread to halt.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendShutdown() {
//            sendMessage(obtainMessage(MSG_SHUTDOWN));
//        }
//
//        /**
//         * Sends the "frame available" message.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendFrameAvailable() {
//            sendMessage(obtainMessage(MSG_FRAME_AVAILABLE));
//        }
//
//        /**
//         * Sends the "zoom value" message.  "progress" should be 0-100.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendZoomValue(int progress) {
//            sendMessage(obtainMessage(MSG_ZOOM_VALUE, progress, 0));
//        }
//
//        /**
//         * Sends the "size value" message.  "progress" should be 0-100.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendSizeValue(int progress) {
//            sendMessage(obtainMessage(MSG_SIZE_VALUE, progress, 0));
//        }
//
//        /**
//         * Sends the "rotate value" message.  "progress" should be 0-100.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendRotateValue(int progress) {
//            sendMessage(obtainMessage(MSG_ROTATE_VALUE, progress, 0));
//        }
//
//        /**
//         * Sends the "position" message.  Sets the position of the rect.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendPosition(int x, int y) {
//            sendMessage(obtainMessage(MSG_POSITION, x, y));
//        }
//
//        /**
//         * Sends the "redraw" message.  Forces an immediate redraw.
//         * <p>
//         * Call from UI thread.
//         */
//        public void sendRedraw() {
//            sendMessage(obtainMessage(MSG_REDRAW));
//        }
//
//        public void sendStartRecording(File file) {
//            sendMessage(obtainMessage(MSG_START_REC, file));
//        }
//
//        public void sendStopRecording() {
//            sendMessage(obtainMessage(MSG_STOP_REC));
//        }
//
//        @Override  // runs on RenderThread
//        public void handleMessage(Message msg) {
//            int what = msg.what;
//            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);
//
//            RenderThread renderThread = mWeakRenderThread.get();
//            if (renderThread == null) {
//                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
//                return;
//            }
//
//            switch (what) {
//                case MSG_SURFACE_AVAILABLE:
//                    renderThread.surfaceAvailable((SurfaceHolder) msg.obj, msg.arg1 != 0);
//                    break;
//                case MSG_SURFACE_CHANGED:
//                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
//                    break;
//                case MSG_SURFACE_DESTROYED:
//                    renderThread.surfaceDestroyed();
//                    break;
//                case MSG_SHUTDOWN:
//                    renderThread.shutdown();
//                    break;
//                case MSG_FRAME_AVAILABLE:
//                    renderThread.frameAvailable();
//                    break;
//                case MSG_ZOOM_VALUE:
//                    renderThread.setZoom(msg.arg1);
//                    break;
//                case MSG_SIZE_VALUE:
//                    renderThread.setSize(msg.arg1);
//                    break;
//                case MSG_ROTATE_VALUE:
//                    renderThread.setRotate(msg.arg1);
//                    break;
//                case MSG_POSITION:
//                    renderThread.setPosition(msg.arg1, msg.arg2);
//                    break;
//                case MSG_REDRAW:
//                    renderThread.draw();
//                    break;
//                case MSG_START_REC:
//                    renderThread.startRecording((File)msg.obj);
//                    break;
//                case MSG_STOP_REC:
//                    renderThread.stopRecording();
//                    break;
//               default:
//                    throw new RuntimeException("unknown message " + what);
//            }
//        }
//    }
}
