package com.zoomda.composable;


import com.zoomda.composable.gles.Drawable2d;
import com.zoomda.composable.gles.ScaledDrawable2d;
import com.zoomda.composable.gles.Sprite2d;
import com.zoomda.composable.gles.WindowSurface;

public class VideoOutput {
    public WindowSurface mWindowSurface;
    public int mWindowSurfaceWidth;
    public int mWindowSurfaceHeight;

    public ScaledDrawable2d mRectDrawable = new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
    public Sprite2d mRect = new Sprite2d(mRectDrawable);
}
