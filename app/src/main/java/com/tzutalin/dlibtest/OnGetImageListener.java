/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tzutalin.dlibtest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import junit.framework.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
public class OnGetImageListener implements OnImageAvailableListener {
    private static final boolean SAVE_PREVIEW_BITMAP = false;

    private static final int INPUT_SIZE = 224;
    private static final String TAG = "OnGetImageListener";

    private int mScreenRotation = 90;

    private int mPreviewWdith = 0;
    private int mPreviewHeight = 0;
    private byte[][] mYUVBytes;
    private int[] mRGBBytes = null;
    private Bitmap mRGBframeBitmap = null;
    private Bitmap mCroppedBitmap = null;

    private boolean mIsComputing = false;
    private Handler mInferenceHandler;

    private Context mContext;
    private FaceDet mFaceDet;
    private TrasparentTitleView mTransparentTitleView;
    private FloatingCameraWindow mWindow;
    private Paint mFaceLandmardkPaint;

    public void initialize(
            final Context context,
            final AssetManager assetManager,
            final TrasparentTitleView scoreView,
            final Handler handler) {
        this.mContext = context;
        this.mTransparentTitleView = scoreView;
        this.mInferenceHandler = handler;
        mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());
        mWindow = new FloatingCameraWindow(mContext);

        mFaceLandmardkPaint = new Paint();
        mFaceLandmardkPaint.setColor(Color.GREEN);
        mFaceLandmardkPaint.setStrokeWidth(2);
        mFaceLandmardkPaint.setStyle(Paint.Style.STROKE);
    }

    public void deInitialize() {
        synchronized (OnGetImageListener.this) {
            if (mFaceDet != null) {
                mFaceDet.release();
            }

            if (mWindow != null) {
                mWindow.release();
            }
        }
    }

    private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {

        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        int screen_height = point.y;
        Log.d(TAG, String.format("screen size (%d,%d)", screen_width, screen_height));
        if (screen_width < screen_height) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
            mScreenRotation = 90;
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
            mScreenRotation = 0;
        }

        Assert.assertEquals(dst.getWidth(), dst.getHeight());
        final float minDim = Math.min(src.getWidth(), src.getHeight());

        final Matrix matrix = new Matrix();

        // We only want the center square out of the original rectangle.
        final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
        final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
        matrix.preTranslate(translateX, translateY);

        final float scaleFactor = dst.getHeight() / minDim;
        matrix.postScale(scaleFactor, scaleFactor);

        // Rotate around the center if necessary.
        if (mScreenRotation != 0) {
            matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
            matrix.postRotate(mScreenRotation);
            matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
        }

        final Canvas canvas = new Canvas(dst);
        canvas.drawBitmap(src, matrix, null);
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            // No mutex needed as this method is not reentrant.
            if (mIsComputing) {
                image.close();
                return;
            }
            mIsComputing = true;

            Trace.beginSection("imageAvailable");

            final Plane[] planes = image.getPlanes();

            // Initialize the storage bitmaps once when the resolution is known.
            if (mPreviewWdith != image.getWidth() || mPreviewHeight != image.getHeight()) {
                mPreviewWdith = image.getWidth();
                mPreviewHeight = image.getHeight();

                Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWdith, mPreviewHeight));
                mRGBBytes = new int[mPreviewWdith * mPreviewHeight];
                mRGBframeBitmap = Bitmap.createBitmap(mPreviewWdith, mPreviewHeight, Config.ARGB_8888);
                mCroppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

                mYUVBytes = new byte[planes.length][];
                for (int i = 0; i < planes.length; ++i) {
                    mYUVBytes[i] = new byte[planes[i].getBuffer().capacity()];
                }
            }

            for (int i = 0; i < planes.length; ++i) {
                planes[i].getBuffer().get(mYUVBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    mPreviewWdith,
                    mPreviewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            Log.e(TAG, "Exception!", e);
            Trace.endSection();
            return;
        }

        mRGBframeBitmap.setPixels(mRGBBytes, 0, mPreviewWdith, 0, 0, mPreviewWdith, mPreviewHeight);
        drawResizedBitmap(mRGBframeBitmap, mCroppedBitmap);

        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(mCroppedBitmap);
        }

        mInferenceHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!new File(Constants.getFaceShapeModelPath()).exists()) {
                            mTransparentTitleView.setText("Copying landmark model to " + Constants.getFaceShapeModelPath());
                            FileUtils.copyFileFromRawToOthers(mContext, R.raw.shape_predictor_68_face_landmarks, Constants.getFaceShapeModelPath());
                        }

                        long startTime = System.currentTimeMillis();
                        List<VisionDetRet> results;
                        synchronized (OnGetImageListener.this) {
                            results = mFaceDet.detect(mCroppedBitmap);
                        }
                        long endTime = System.currentTimeMillis();
                        mTransparentTitleView.setText("Time cost: " + String.valueOf((endTime - startTime) / 1000f) + " sec");
                        // Draw on bitmap
                        if (results != null) {
                            for (final VisionDetRet ret : results) {
                                float resizeRatio = 1.0f;
                                Rect bounds = new Rect();
                                bounds.left = (int) (ret.getLeft() * resizeRatio);
                                bounds.top = (int) (ret.getTop() * resizeRatio);
                                bounds.right = (int) (ret.getRight() * resizeRatio);
                                bounds.bottom = (int) (ret.getBottom() * resizeRatio);
                                Canvas canvas = new Canvas(mCroppedBitmap);
                                canvas.drawRect(bounds, mFaceLandmardkPaint);

                                // Draw landmark
                                ArrayList<Point> landmarks = ret.getFaceLandmarks();
//                                for (Point point : landmarks) {
//                                    int pointX = (int) (point.x * resizeRatio);
//                                    int pointY = (int) (point.y * resizeRatio);
//                                    canvas.drawCircle(pointX, pointY, 2, mFaceLandmardkPaint);
//                                }

                                // mount area
//                                int mStart = 48;
//                                int mEnd = 68;
//                                int min_x = 999;
//                                int min_y = 999;
//                                int max_x = 0;
//                                int max_y = 0;
//                                for (int pt_id = mStart; pt_id<mEnd; pt_id++){
//                                    int loc_x = landmarks.get(pt_id).x;
//                                    int loc_y = landmarks.get(pt_id).y;
//                                    if (loc_x < min_x){min_x = loc_x;}
//                                    if (loc_x > max_x){max_x = loc_x;}
//                                    if (loc_y < min_y){min_y = loc_y;}
//                                    if (loc_y > max_y){max_y = loc_y;}
//                                }
//
//                                // Calculate saturation of mouth
//                                min_x = (int) (min_x * resizeRatio);
//                                max_x = (int) (max_x * resizeRatio);
//                                min_y = (int) (min_y * resizeRatio);
//                                max_y = (int) (max_y * resizeRatio);
//
//                                float sum_saturation = 0;
//                                for (int mx = min_x; mx < max_x; mx ++){
//                                    for (int my = min_y; my < max_y; my ++){
//                                        int mcl = mCroppedBitmap.getPixel(mx, my);
//                                        int r = Color.red(mcl);
//                                        int g = Color.green(mcl);
//                                        int b = Color.blue(mcl);
//
//                                        float[] hsv = new float[3];
//                                        Color.RGBToHSV(r, g, b, hsv);
//                                        sum_saturation += hsv[1];
////                                        mCroppedBitmap.setPixel(mx, my, (int)hsv[1]);
//                                    }
//                                }
//
//                                float area = (max_y - min_y) * (max_x - min_x);
//                                float avg_saturation = sum_saturation / area;
//
//                                double roundOff = Math.round(avg_saturation * 100.0) / 100.0;
//                                float newWidth =  mCroppedBitmap.getWidth();
//                                float newHeight =  mCroppedBitmap.getHeight();
//
//                                canvas.drawText(Double.toString(roundOff), (int)(newWidth / 3), newHeight - 5, mFaceLandmardkPaint);
//                                if (avg_saturation > 100){
//                                    // Mouth detected
//                                    canvas.drawText("Chưa đeo khẩu trang", (int)(newWidth / 3), newHeight - 5, paint);
//                                }

                                // Comparing intensity
                                // Middle area of 2 eyebrow (landmark 21, 22)
//                                float avg_eyebrow = calculate_avg_color(
//                                        (int)(landmarks.get(21).x*resizeRatio),
//                                        (int)(landmarks.get(22).x*resizeRatio),
//                                        (int)(landmarks.get(21).y*resizeRatio),
//                                        (int)(landmarks.get(22).y*resizeRatio));

                                // Middle of 2 eyes (landmark 39, 42)
//                                float avg_eyes = calculate_avg_color(
//                                        (int)(landmarks.get(39).x*resizeRatio),
//                                        (int)(landmarks.get(42).x*resizeRatio),
//                                        (int)(landmarks.get(39).y*resizeRatio),
//                                        (int)(landmarks.get(42).y*resizeRatio));

                                // Middle area of uper area
                                float avg_eyesArea = calculate_avg_color(
                                        (int)(landmarks.get(21).x*resizeRatio),
                                        (int)(landmarks.get(42).x*resizeRatio),
                                        (int)(landmarks.get(21).y*resizeRatio),
                                        (int)(landmarks.get(42).y*resizeRatio));

                                // Left mouth (landmark 4, 48)
                                float avg_lmouth = calculate_avg_color(
                                        (int)(landmarks.get(4).x*resizeRatio),
                                        (int)(landmarks.get(48).x*resizeRatio),
                                        (int)(landmarks.get(4).y*resizeRatio),
                                        (int)(landmarks.get(48).y*resizeRatio));

                                // Right mouth (landmark 54, 12)
                                float avg_rmouth = calculate_avg_color(
                                        (int)(landmarks.get(54).x*resizeRatio),
                                        (int)(landmarks.get(12).x*resizeRatio),
                                        (int)(landmarks.get(54).y*resizeRatio),
                                        (int)(landmarks.get(12).y*resizeRatio));

                                double diff =  100 - Math.abs(avg_eyesArea - ((avg_lmouth + avg_rmouth)/2))/ Math.max(avg_eyesArea,((avg_lmouth + avg_rmouth)/2)) * 100;
                                float newWidth =  mCroppedBitmap.getWidth();
                                float newHeight =  mCroppedBitmap.getHeight();
                                canvas.drawText(Double.toString(diff), (int)(newWidth / 3), newHeight - 5, mFaceLandmardkPaint);



                            }
                        }

                        mWindow.setRGBBitmap(mCroppedBitmap);
                        mIsComputing = false;
                    }
                });

        Trace.endSection();
    }

    public float calculate_avg_color(int minx, int maxx, int miny, int maxy){
        float avg_color = 0;
        int count_pixel = 0;
        int tmp = 0;
        if (minx > maxx){tmp = maxx; maxx = minx; minx=tmp;}
        if (miny > maxy){tmp = maxy; maxy = miny; miny=tmp;}

        for (int x=minx; x<maxx; x++){
            for (int y=miny-1; y<=maxy+1; y++){
                count_pixel += 1;
                int mcl = mCroppedBitmap.getPixel(x, y);
                int r = Color.red(mcl);
                int g = Color.green(mcl);
                int b = Color.blue(mcl);

                float[] hsv = new float[3];
                Color.RGBToHSV(r, g, b, hsv);
                avg_color += hsv[0];
            }
        }
        avg_color = avg_color / count_pixel;

        return avg_color;
    }
}
