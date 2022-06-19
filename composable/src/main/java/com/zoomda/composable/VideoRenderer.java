package com.zoomda.composable;

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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.SeekBar;

import com.zoomda.composable.gles.EglCore;
import com.zoomda.composable.gles.GlUtil;
import com.zoomda.composable.gles.Texture2dProgram;
import com.zoomda.composable.gles.WindowSurface;
import com.zoomda.composable.utils.CameraUtils;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class VideoRenderer {
    private static final String TAG = VideoRenderer.class.getSimpleName();

    private static final int DEFAULT_ZOOM_PERCENT = 0;      // 0-100
    private static final int DEFAULT_SIZE_PERCENT = 50;     // 0-100
    private static final int DEFAULT_ROTATE_PERCENT = 0;    // 0-100
    private static final int REQ_CAMERA_WIDTH = 1280;
    private static final int REQ_CAMERA_HEIGHT = 720;
    private static final int REQ_CAMERA_FPS = 30;

    private static TextureMovieEncoder mVideoEncoder = new TextureMovieEncoder();
    private RenderThread mRenderThread;

    public void start(SurfaceHolder sSurfaceHolder){
        mRenderThread = new RenderThread();
        //Drawable drawable = getDrawable(R.drawable.auto_pilot_off);
        //BitmapDrawable drawableBitmap = (BitmapDrawable) drawable;
        //mRenderThread.setBitmap(drawableBitmap.getBitmap());

        mRenderThread.setName("TexFromCam Render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
        rh.sendZoomValue(DEFAULT_ZOOM_PERCENT);
        rh.sendSizeValue(DEFAULT_SIZE_PERCENT);
        rh.sendRotateValue(DEFAULT_ROTATE_PERCENT);

        if (sSurfaceHolder != null) {
            Log.d(TAG, "Sending previous surface");
            rh.sendSurfaceAvailable(sSurfaceHolder, false);
        } else {
            Log.d(TAG, "No previous surface");
        }
    }

    public void stop(){
        if (mRenderThread == null) {
            return;
        }
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendShutdown();
        try {
            mRenderThread.join();
        } catch (InterruptedException ie) {
            // not expected
            throw new RuntimeException("join was interrupted", ie);
        }
        mRenderThread = null;
    }

    public void onSurfaceCreated(SurfaceHolder holder){
        if (mRenderThread != null) {
            // Normal case -- render thread is running, tell it about the new surface.
            RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceAvailable(holder, true);
        } else {
            // Sometimes see this on 4.4.x N5: power off, power on, unlock, with device in
            // landscape and a lock screen that requires portrait.  The surface-created
            // message is showing up after onPause().
            //
            // Chances are good that the surface will be destroyed before the activity is
            // unpaused, but we track it anyway.  If the activity is un-paused and we start
            // the RenderThread, the SurfaceHolder will be passed in right after the thread
            // is created.
            Log.d(TAG, "render thread not running");
        }
    }

    public void onSurfaceChanged(int format, int width, int height){
        if (mRenderThread != null) {
            RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceChanged(format, width, height);
        } else {
            Log.d(TAG, "Ignoring surfaceChanged");
            return;
        }
    }

    public void onSurfaceDestroyed(SurfaceHolder holder){
        // In theory we should tell the RenderThread that the surface has been destroyed.
        if (mRenderThread != null) {
            RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceDestroyed();
        }
        Log.d(TAG, "surfaceDestroyed holder=" + holder);
    }

    public void onProgressChangedZoom(int progress){
        if (mRenderThread == null) {
            // Could happen if we programmatically update the values after setting a listener
            // but before starting the thread.  Also, easy to cause this by scrubbing the seek
            // bar with one finger then tapping "recents" with another.
            Log.w(TAG, "Ignoring onProgressChanged received w/o RT running");
            return;
        }
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendZoomValue(progress);


        // If we're getting preview frames quickly enough we don't really need this, but
        // we don't want to have chunky-looking resize movement if the camera is slow.
        // OTOH, if we get the updates too quickly (60fps camera?), this could jam us
        // up and cause us to run behind.  So use with caution.
        rh.sendRedraw();
    }

    public void onProgressChangedSize(int progress){
        if (mRenderThread == null) {
            // Could happen if we programmatically update the values after setting a listener
            // but before starting the thread.  Also, easy to cause this by scrubbing the seek
            // bar with one finger then tapping "recents" with another.
            Log.w(TAG, "Ignoring onProgressChanged received w/o RT running");
            return;
        }
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendSizeValue(progress);

        rh.sendRedraw();
    }

    public void onProgressChangedRotate(int progress){
        if (mRenderThread == null) {
            // Could happen if we programmatically update the values after setting a listener
            // but before starting the thread.  Also, easy to cause this by scrubbing the seek
            // bar with one finger then tapping "recents" with another.
            Log.w(TAG, "Ignoring onProgressChanged received w/o RT running");
            return;
        }
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendRotateValue(progress);

        rh.sendRedraw();
    }

    public void setPosition(float x, float y){
        if (mRenderThread != null) {
            RenderHandler rh = mRenderThread.getHandler();
            rh.sendPosition((int) x, (int) y);

            // Forcing a redraw can cause sluggish-looking behavior if the touch
            // events arrive quickly.
            //rh.sendRedraw();
        }
    }

    public void startRecording(File outputFile){
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendStartRecording(outputFile);
    }

    public void stopRecording(){
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendStopRecording();
    }

    public void addImage(Bitmap bitmap){
        RenderHandler rh = mRenderThread.getHandler();
        rh.sendAddImage(bitmap);

    }


    //
    //
    // ========================================================================
    // ========================================================================
    // ========================================================================
    // ========================================================================
    // ========================================================================
    //
    //
    private static class RenderThread extends Thread implements
            SurfaceTexture.OnFrameAvailableListener {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        //private MainHandler mMainHandler;

        private Camera mCamera;
        private int mCameraPreviewWidth, mCameraPreviewHeight;

        private EglCore mEglCore;

        // Orthographic projection matrix.
        private float[] mDisplayProjectionMatrix = new float[16];
        private float[] mCodecProjectionMatrix = new float[16];

        // Receives the output from the camera preview.
        //private VideoInput mCameraVideoInput = new VideoInput();
        private ArrayList<VideoInput> mSourceList = new ArrayList<>();

        private int mZoomPercent = DEFAULT_ZOOM_PERCENT;
        private int mSizePercent = DEFAULT_SIZE_PERCENT;
        private int mRotatePercent = DEFAULT_ROTATE_PERCENT;
        private float mPosX, mPosY;
        private boolean mRecordingStatus = false;
        private File mOutputFile;

        private VideoOutput mViewerOutput = new VideoOutput();
        private boolean finishSurfaceAvailable = false;
        private boolean startFinishSurfaceSetup = false;
        private boolean finishSurfaceChanged = false;

        /**
         * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
         * Activity.
         */
        public RenderThread() {
            //mMainHandler = handler;
        }

        /**
         * Thread entry point.
         */
        @Override
        public void run() {
            Looper.prepare();

            // We need to create the Handler before reporting ready.
            mHandler = new RenderHandler(this);
            synchronized (mStartLock) {
                mReady = true;
                mStartLock.notify();    // signal waitUntilReady()
            }

            // Prepare EGL and open the camera before we start handling messages.
            mEglCore = new EglCore(null, 0);
            openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_CAMERA_FPS);

            Looper.loop();

            Log.d(TAG, "looper quit");
            releaseCamera();
            releaseGl();
            mEglCore.release();

            synchronized (mStartLock) {
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        private void addImage(float scale, Bitmap bitmap){
            VideoInput mImageVideoInput = new VideoInput();
            mImageVideoInput.scale = scale;
            mImageVideoInput.texProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
            mImageVideoInput.textureId = mImageVideoInput.texProgram.createTextureObject();
            Log.d(TAG, "mImageVideoInput.textureId: "+mImageVideoInput.textureId);
            mImageVideoInput.surfaceTexture = new SurfaceTexture(mImageVideoInput.textureId);
            mImageVideoInput.surfaceTexture.setDefaultBufferSize(640, 480);
            mImageVideoInput.surface = new Surface(mImageVideoInput.surfaceTexture);

            Canvas c;
            if (Build.VERSION.SDK_INT >= 23) {
                c = mImageVideoInput.surface.lockHardwareCanvas();
            } else {
                c = mImageVideoInput.surface.lockCanvas(null);
            }
            if (c != null) {
                try
                {
                    c.drawColor( 0, PorterDuff.Mode.CLEAR );
                    c.drawBitmap(bitmap, 0, 0, null);
                } catch (Exception e){
                    Log.d(TAG, e.getMessage());
                }
                mImageVideoInput.surface.unlockCanvasAndPost(c);
            }

            mSourceList.add(mImageVideoInput);
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
         */
        private void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            Surface surface = holder.getSurface();
            mViewerOutput.mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mViewerOutput.mWindowSurface.makeCurrent();

            // Create and configure the SurfaceTexture, which will receive frames from the
            // camera.  We set the textured rect's program to render from it.

            VideoInput mCameraVideoInput = new VideoInput();
            mCameraVideoInput.scale = 1.0f;
            mCameraVideoInput.texProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
            mCameraVideoInput.textureId = mCameraVideoInput.texProgram.createTextureObject();
            Log.d(TAG, "mCameraVideoInput.textureId: "+mCameraVideoInput.textureId);
            mCameraVideoInput.surfaceTexture = new SurfaceTexture(mCameraVideoInput.textureId);

            if (!newSurface) {
                // This Surface was established on a previous run, so no surfaceChanged()
                // message is forthcoming.  Finish the surface setup now.
                //
                // We could also just call this unconditionally, and perhaps do an unnecessary
                // bit of reallocating if a surface-changed message arrives.
                mViewerOutput.mWindowSurfaceWidth = mViewerOutput.mWindowSurface.getWidth();
                mViewerOutput.mWindowSurfaceHeight = mViewerOutput.mWindowSurface.getHeight();
                finishSurfaceSetup();
            }

            mSourceList.add(mCameraVideoInput);



            mSourceList.get(0).surfaceTexture.setOnFrameAvailableListener(this);
            //mCameraVideoInput.surfaceTexture.setOnFrameAvailableListener(this);

            finishSurfaceAvailable = true;
            if(!startFinishSurfaceSetup && finishSurfaceChanged)
                finishSurfaceSetup();
        }

        /**
         * Releases most of the GL resources we currently hold (anything allocated by
         * surfaceAvailable()).
         * <p>
         * Does not release EglCore.
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            if (mViewerOutput.mWindowSurface != null) {
                mViewerOutput.mWindowSurface.release();
                mViewerOutput.mWindowSurface = null;
            }
            for (int i=0; i<mSourceList.size(); i++){
                VideoInput VideoInput = mSourceList.get(i);
                if (VideoInput.surfaceTexture != null) {
                    Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
                    VideoInput.surfaceTexture.release();
                    VideoInput.surfaceTexture = null;
                }
            }
            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Handles the surfaceChanged message.
         * <p>
         * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
         * could also be called with a Surface created on a previous run.  So this may not
         * be called.
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height);

            mViewerOutput.mWindowSurfaceWidth = width;
            mViewerOutput.mWindowSurfaceHeight = height;
            if(finishSurfaceAvailable)
                finishSurfaceSetup();
            finishSurfaceChanged = true;
        }

        /**
         * Handles the surfaceDestroyed message.
         */
        private void surfaceDestroyed() {
            // In practice this never appears to be called -- the activity is always paused
            // before the surface is destroyed.  In theory it could be called though.
            Log.d(TAG, "RenderThread surfaceDestroyed");
            releaseGl();
        }

        /**
         * Sets up anything that depends on the window size.
         * <p>
         * Open the camera (to set mCameraAspectRatio) before calling here.
         */
        private synchronized void finishSurfaceSetup() {
            startFinishSurfaceSetup = true;
            int width = mViewerOutput.mWindowSurfaceWidth;
            int height = mViewerOutput.mWindowSurfaceHeight;
            Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
                    " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);

            // Use full window.
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            // Default position is center of screen.
            mPosX = width / 2.0f;
            mPosY = height / 2.0f;
            Log.d(TAG, "mPosX: "+mPosX);
            Log.d(TAG, "mPosY: "+mPosY);

            updateGeometry();

            // Ready to go, start the camera.
            Log.d(TAG, "starting camera preview");
            try {
                mCamera.setPreviewTexture(mSourceList.get(0).surfaceTexture);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            try{
                mCamera.startPreview();
            }catch (Throwable t){}
        }

        /**
         * Updates the geometry of mRect, based on the size of the window and the current
         * values set by the UI.
         */
        private void updateGeometry() {
            int width = mViewerOutput.mWindowSurfaceWidth;
            int height = mViewerOutput.mWindowSurfaceHeight;

            int smallDim = Math.min(width, height);
            // Max scale is a bit larger than the screen, so we can show over-size.
            float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
            float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
            int newWidth = Math.round(scaled * cameraAspect);
            int newHeight = Math.round(scaled);

            float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
            int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));

            Log.d(TAG, "newWidth: "+newWidth);
            Log.d(TAG, "newHeight: "+newHeight);
            mViewerOutput.mRect.setScale(newWidth, newHeight);
            mViewerOutput.mRect.setPosition(mPosX, mPosY);
            mViewerOutput.mRect.setRotation(rotAngle);
            mViewerOutput.mRectDrawable.setScale(zoomFactor);

//            mMainHandler.sendRectSize(newWidth, newHeight);
//            mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
//                    Math.round(mCameraPreviewHeight * zoomFactor));
//            mMainHandler.sendRotateDeg(rotAngle);
        }

        @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mHandler.sendFrameAvailable();
        }

        /**
         * Handles incoming frame of data from the camera.
         */
        private void frameAvailable() {
            draw();
        }

        private void startRecording(File file){
            mRecordingStatus = true;
            this.mOutputFile = file;
        }

        private void stopRecording(){
            mRecordingStatus = false;
        }

        /**
         * Draws the scene and submits the buffer.
         */
        private void draw() {
            GlUtil.checkGlError("draw start");

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);

            GLES20.glViewport(0, 0, mViewerOutput.mWindowSurfaceWidth, mViewerOutput.mWindowSurfaceHeight);

            for (int i=0; i<mSourceList.size(); i++)
            {
                mSourceList.get(i).surfaceTexture.updateTexImage();
                mViewerOutput.mRect.setTexture(mSourceList.get(i).textureId);

                float scaleX = mViewerOutput.mRect.getScaleX();
                float scaleY = mViewerOutput.mRect.getScaleY();
                mViewerOutput.mRect.setScale(scaleX*mSourceList.get(i).scale, scaleY*mSourceList.get(i).scale);

                mViewerOutput.mRect.setRotation(mSourceList.get(i).angle);

                mViewerOutput.mRect.draw(mSourceList.get(i).texProgram, mDisplayProjectionMatrix);
                mViewerOutput.mRect.setScale(scaleX, scaleY);
                //Log.d(TAG, "scaleX: "+i+" "+scaleX);
                //Log.d(TAG, "scaleY: "+i+" "+scaleY);
            }
            mViewerOutput.mWindowSurface.swapBuffers();

            if(mVideoEncoder.isRecording()){
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_CULL_FACE);

                GLES20.glViewport(0, 0, 640, 360);

//                for (int i=0; i<mSourceList.size(); i++)
//                {
//                    mVideoEncoder.setTextureId(mSourceList.get(i).textureId);
//                    mVideoEncoder.frameAvailable(mSourceList.get(i).surfaceTexture);
//                }

                Matrix.orthoM(mCodecProjectionMatrix, 0, 0, 640, 0, 360, -1, 1);
                mVideoEncoder.setDisplayProjectionMatrix(mCodecProjectionMatrix);
                mVideoEncoder.drawAllSources(mSourceList);
            }

            if(mRecordingStatus && !mVideoEncoder.isRecording()){
                mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                        mOutputFile, 640, 360, 1000000, EGL14.eglGetCurrentContext()));
            }else if(!mRecordingStatus && mVideoEncoder.isRecording()){
                mVideoEncoder.stopRecording();
            }

            GlUtil.checkGlError("draw done");
        }

        private void setZoom(int percent) {
            mZoomPercent = percent;
            updateGeometry();
        }

        private void setSize(int percent) {
            mSizePercent = percent;
            updateGeometry();
        }

        private void setRotate(int percent) {
            mRotatePercent = percent;
            updateGeometry();
        }

        private void setPosition(int x, int y) {
            mPosX = x;
            mPosY = mViewerOutput.mWindowSurfaceHeight - y;   // GLES is upside-down
            updateGeometry();
        }


        /**
         * Opens a camera, and attempts to establish preview mode at the specified width
         * and height with a fixed frame rate.
         * <p>
         * Sets mCameraPreviewWidth / mCameraPreviewHeight.
         */
        private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
            if (mCamera != null) {
                throw new RuntimeException("camera already initialized");
            }

            Camera.CameraInfo info = new Camera.CameraInfo();

            // Try to find a front-facing camera (e.g. for videoconferencing).
            int numCameras = Camera.getNumberOfCameras();
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCamera = Camera.open(i);
                    break;
                }
            }
            if (mCamera == null) {
                Log.d(TAG, "No front-facing camera found; opening default");
                mCamera = Camera.open();    // opens first back-facing camera
            }
            if (mCamera == null) {
                throw new RuntimeException("Unable to open camera");
            }

            Camera.Parameters parms = mCamera.getParameters();

            CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

            // Try to set the frame rate to a constant value.
            int thousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

            // Give the camera a hint that we're recording video.  This can have a big
            // impact on frame rate.
            parms.setRecordingHint(true);

            mCamera.setParameters(parms);

            int[] fpsRange = new int[2];
            Camera.Size mCameraPreviewSize = parms.getPreviewSize();
            parms.getPreviewFpsRange(fpsRange);
            String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
            if (fpsRange[0] == fpsRange[1]) {
                previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
            } else {
                previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                        " - " + (fpsRange[1] / 1000.0) + "] fps";
            }
            Log.i(TAG, "Camera config: " + previewFacts);

            mCameraPreviewWidth = mCameraPreviewSize.width;
            mCameraPreviewHeight = mCameraPreviewSize.height;
//            mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight,
//                    thousandFps / 1000.0f);
        }

        /**
         * Stops camera preview, and releases the camera to the system.
         */
        private void releaseCamera() {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
                Log.d(TAG, "releaseCamera -- done");
            }
        }
    }

    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_AVAILABLE = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_SURFACE_DESTROYED = 2;
        private static final int MSG_SHUTDOWN = 3;
        private static final int MSG_FRAME_AVAILABLE = 4;
        private static final int MSG_ZOOM_VALUE = 5;
        private static final int MSG_SIZE_VALUE = 6;
        private static final int MSG_ROTATE_VALUE = 7;
        private static final int MSG_POSITION = 8;
        private static final int MSG_REDRAW = 9;
        private static final int MSG_START_REC = 10;
        private static final int MSG_STOP_REC = 11;
        private static final int MSG_ADD_IMAGE = 12;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface available" message.  If the surface was newly created (i.e.
         * this is called from surfaceCreated()), set newSurface to true.  If this is
         * being called during Activity startup for a previously-existing surface, set
         * newSurface to false.
         * <p>
         * The flag tells the caller whether or not it can expect a surfaceChanged() to
         * arrive very soon.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            Log.d(TAG, "sendSurfaceAvailable newSurface: "+newSurface);
            sendMessage(obtainMessage(MSG_SURFACE_AVAILABLE,
                    newSurface ? 1 : 0, 0, holder));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
                                       int height) {
            // ignore format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceDestroyed() {
            sendMessage(obtainMessage(MSG_SURFACE_DESTROYED));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN));
        }

        /**
         * Sends the "frame available" message.
         * <p>
         * Call from UI thread.
         */
        public void sendFrameAvailable() {
            sendMessage(obtainMessage(MSG_FRAME_AVAILABLE));
        }

        /**
         * Sends the "zoom value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendZoomValue(int progress) {
            sendMessage(obtainMessage(MSG_ZOOM_VALUE, progress, 0));
        }

        /**
         * Sends the "size value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendSizeValue(int progress) {
            sendMessage(obtainMessage(MSG_SIZE_VALUE, progress, 0));
        }

        /**
         * Sends the "rotate value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendRotateValue(int progress) {
            sendMessage(obtainMessage(MSG_ROTATE_VALUE, progress, 0));
        }

        /**
         * Sends the "position" message.  Sets the position of the rect.
         * <p>
         * Call from UI thread.
         */
        public void sendPosition(int x, int y) {
            sendMessage(obtainMessage(MSG_POSITION, x, y));
        }

        /**
         * Sends the "redraw" message.  Forces an immediate redraw.
         * <p>
         * Call from UI thread.
         */
        public void sendRedraw() {
            sendMessage(obtainMessage(MSG_REDRAW));
        }

        public void sendStartRecording(File file) {
            sendMessage(obtainMessage(MSG_START_REC, file));
        }

        public void sendStopRecording() {
            sendMessage(obtainMessage(MSG_STOP_REC));
        }

        public void sendAddImage(Bitmap bitmap) {
            sendMessage(obtainMessage(MSG_ADD_IMAGE, bitmap));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_AVAILABLE:
                    renderThread.surfaceAvailable((SurfaceHolder) msg.obj, msg.arg1 != 0);
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_SURFACE_DESTROYED:
                    renderThread.surfaceDestroyed();
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                case MSG_FRAME_AVAILABLE:
                    renderThread.frameAvailable();
                    break;
                case MSG_ZOOM_VALUE:
                    renderThread.setZoom(msg.arg1);
                    break;
                case MSG_SIZE_VALUE:
                    renderThread.setSize(msg.arg1);
                    break;
                case MSG_ROTATE_VALUE:
                    renderThread.setRotate(msg.arg1);
                    break;
                case MSG_POSITION:
                    renderThread.setPosition(msg.arg1, msg.arg2);
                    break;
                case MSG_REDRAW:
                    renderThread.draw();
                    break;
                case MSG_START_REC:
                    renderThread.startRecording((File)msg.obj);
                    break;
                case MSG_STOP_REC:
                    renderThread.stopRecording();
                    break;
                case MSG_ADD_IMAGE:
                    renderThread.addImage(1.0f, (Bitmap) msg.obj);
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
