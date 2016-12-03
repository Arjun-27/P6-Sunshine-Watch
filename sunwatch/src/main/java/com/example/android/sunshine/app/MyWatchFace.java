/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String TAG = "SunWatchService";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements MessageApi.MessageListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
            /*NodeApi.NodeListener*/ CapabilityApi.CapabilityListener {

        private static final String CONNECTION_STATUS_CAPABILITY_NAME = "fetch_weather_data_capability";


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Paint mLinePaint;

        boolean mAmbient;
        boolean isColonVisible;
        Calendar mCalendar;

        static final String DATA_PATH = "/wearable/sunwatch/data";
        static final String DATA_READY = "dataReady";

        Bitmap mWeatherBitmap;
        String mMaxTemp;
        String mMinTemp;
        String todaysDate;

        float mMaxTempWidth;

        GoogleApiClient mGoogleApiClient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mCenterXWeatherBitmapOffset;
        float mCenterYWeatherBitmapOffset;
        float mHourWidth;
        float mColonWidth;
        float mTempTextHalfHeight;
        float mWeatherBitmapHalfHeight;
        int mWeatherBitmapWidth;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .setHotwordIndicatorGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM)
                    .build());

            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_time_offset);
            mCenterYWeatherBitmapOffset = resources.getDimension(R.dimen.digital_y_weather_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(MyWatchFace.this, R.color.background));

            mTimePaint = createTextPaint(ContextCompat.getColor(MyWatchFace.this, R.color.digital_text));
            mTimePaint.setTypeface(NORMAL_TYPEFACE);

            mMaxTempPaint = createTextPaint(ContextCompat.getColor(MyWatchFace.this, R.color.digital_text));
            mMaxTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));

            mMinTempPaint = createTextPaint(ContextCompat.getColor(MyWatchFace.this, R.color.digital_text_faint));
            mMinTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));

            mLinePaint = new Paint();
            mLinePaint.setColor(ContextCompat.getColor(MyWatchFace.this, R.color.color_line));
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeWidth(0);

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.CapabilityApi.removeCapabilityListener(mGoogleApiClient, this, CONNECTION_STATUS_CAPABILITY_NAME);
                    Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
            mXOffset = mTimePaint.measureText("00:00") / 2;
            mHourWidth = mTimePaint.measureText("00");
            mColonWidth = mTimePaint.measureText(":");
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                }
            }

            invalidate();
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), todaysDate, Toast.LENGTH_SHORT).show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            mCalendar.setTimeInMillis(System.currentTimeMillis());
            todaysDate = new SimpleDateFormat("EEE, d MMM ''yy", Locale.getDefault()).format(new Date(System.currentTimeMillis()));

            isColonVisible = isInAmbientMode() || mCalendar.get(Calendar.SECOND) % 2 == 0;

            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float hourXOffset = centerX - mXOffset;
            float hourYOffset = centerY - mYOffset;

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String hour = String.format(Locale.getDefault(), "%02d", mCalendar.get(Calendar.HOUR_OF_DAY));
            canvas.drawText(hour, hourXOffset, hourYOffset, mTimePaint);

            if (isColonVisible) {
                canvas.drawText(":", hourXOffset + mHourWidth, hourYOffset, mTimePaint);
            }

            String mins = String.format(Locale.getDefault(), "%02d", mCalendar.get(Calendar.MINUTE));
            canvas.drawText(mins, hourXOffset + mHourWidth + mColonWidth, hourYOffset, mTimePaint);


            canvas.drawLine((bounds.width()/2.5f), centerY, (0.625f * bounds.width()), centerY, mLinePaint);

            float bitmapXOffset = centerX - mCenterXWeatherBitmapOffset;
            float bitmapYOffset = centerY + mCenterYWeatherBitmapOffset;

            if (mWeatherBitmap != null && !isInAmbientMode()) {
                canvas.drawBitmap(mWeatherBitmap, bitmapXOffset, bitmapYOffset, null);
            }

            if (mMaxTemp != null) {
                canvas.drawText(mMaxTemp, bitmapXOffset + mWeatherBitmapWidth, bitmapYOffset + mWeatherBitmapHalfHeight + mTempTextHalfHeight, mMaxTempPaint);
            }

            if (mMinTemp != null) {
                canvas.drawText(mMinTemp, bitmapXOffset + mWeatherBitmapWidth + mMaxTempWidth, bitmapYOffset + mWeatherBitmapHalfHeight + mTempTextHalfHeight, mMinTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    CapabilityApi.GetCapabilityResult result = Wearable.CapabilityApi.getCapability(mGoogleApiClient, CONNECTION_STATUS_CAPABILITY_NAME, CapabilityApi.FILTER_REACHABLE).await();

                    updateConnectionCapability(result.getCapability());
                }
            }).start();

            Wearable.CapabilityApi.addCapabilityListener(mGoogleApiClient, this, CONNECTION_STATUS_CAPABILITY_NAME);
            Wearable.MessageApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Wearable.CapabilityApi.removeCapabilityListener(mGoogleApiClient, this, CONNECTION_STATUS_CAPABILITY_NAME);
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Wearable.CapabilityApi.removeCapabilityListener(mGoogleApiClient, this, CONNECTION_STATUS_CAPABILITY_NAME);
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);

        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            if(messageEvent.getPath().equals(DATA_PATH)) {
                try {
                    Log.d(TAG, "Yooo");
                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(messageEvent.getData());
                    ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);

                    List<byte[]> byteList = (List<byte[]>) objectInputStream.readObject();

                    mWeatherBitmap = BitmapFactory.decodeByteArray(byteList.get(0), 0, byteList.get(0).length);

                    mWeatherBitmapWidth = mWeatherBitmap.getWidth();
                    mMaxTemp = new String(byteList.get(1));
                    mMinTemp = new String(byteList.get(2));

                    mMaxTemp = " " + mMaxTemp;
                    mMinTemp = " " + mMinTemp;

                    mMaxTempWidth = mMaxTempPaint.measureText(mMaxTemp);
                    float totalTempLen = mMaxTempWidth + mMinTempPaint.measureText(mMinTemp);

                    mCenterXWeatherBitmapOffset = (mWeatherBitmapWidth + totalTempLen) / 2f;
                    mWeatherBitmapHalfHeight = mWeatherBitmap.getHeight() / 2f;

                    Rect bounds = new Rect();
                    mMaxTempPaint.getTextBounds(mMaxTemp, 0, mMaxTemp.length(), bounds);
                    mTempTextHalfHeight = bounds.height() / 2f;

                    invalidate();
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }

        private void updateConnectionCapability(CapabilityInfo info) {
            Set<Node> connectedNodes = info.getNodes();
            Node nodeToUse = null;
            if(connectedNodes.isEmpty()) {
                Log.d(TAG, "Connection Lost!!");
            } else {
                for(Node node : connectedNodes) {
                    if(node.isNearby()) {
                        nodeToUse = node;
                        break;
                    }
                }
                if(nodeToUse != null)
                    sendReadyMessageToHandheld(nodeToUse);
            }
        }

        private void sendReadyMessageToHandheld(final Node node) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Wearable.MessageApi.sendMessage(
                            mGoogleApiClient,
                            node.getId(),
                            DATA_PATH,
                            DATA_READY.getBytes()).await();
                }
            }).start();
        }

        @Override
        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
            updateConnectionCapability(capabilityInfo);
        }
    }
}