package com.android.grafika;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.android.grafika.gles.Texture2dProgram;

public class VideoInput {
    public Texture2dProgram texProgram;
    public int textureId = -1;
    public SurfaceTexture 	surfaceTexture;
    public Surface surface;
    public float scale = 1.0f;
    public float angle = 270.0f;

    //public VideoSource(){}
}
