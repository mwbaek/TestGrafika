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
            PermissionHelper.requestCameraPermission(this, true);
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

            try{
                mVideoRenderer.startRecording(outputFile);
            }catch (Throwable t){
                PermissionHelper.requestWriteStoragePermission(this);
                mRecordingEnabled = false;
                updateControls();
            }
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

}
