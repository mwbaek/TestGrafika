package com.android.grafika;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.WindowSurface;

public class VideoOutput {
    public WindowSurface mWindowSurface;
    public int mWindowSurfaceWidth;
    public int mWindowSurfaceHeight;

    public ScaledDrawable2d mRectDrawable = new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
    public Sprite2d mRect = new Sprite2d(mRectDrawable);
}
