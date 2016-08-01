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
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with no seconds. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    public final String LOG_TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a minute since seconds are
     * not displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(60);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    // keys for shared prefs
    private final String LAST_HIGH_TEMP = "LastHighTemp";
    private final String LAST_LOW_TEMP = "LastLowTemp";
    private final String LAST_ICON = "LastIcon";

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mTempPaint;
        Paint mUpdatedPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        Date mDate;

        String mIcon = "0";
        int mPng = 0;
        String mHighTemp = "-";
        String mLowTemp = "-";
        String mUpdated = "";
        Bitmap mWeatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            int textColor = ContextCompat.getColor(getApplicationContext(), R.color.digital_text);
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(textColor);

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(textColor);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_size));

            mTempPaint = new Paint();
            mTempPaint = createTextPaint(textColor);
            mTempPaint.setTextSize(resources.getDimension(R.dimen.weather_text_size));

            int updatedColor = ContextCompat.getColor(getApplicationContext(), R.color.updated_text);
            mUpdatedPaint = new Paint();
            mUpdatedPaint = createTextPaint(updatedColor);
            mUpdatedPaint.setTextSize(resources.getDimension(R.dimen.updated_text_size));

            mTime = new Time();

            // load the default weather icon
            mWeatherIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(),R.drawable.ic_clear);
            getInfoFromSharedPrefs();
            registerSharedPrefsListener();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

            super.onDestroy();

            unregisterSharedPrefsListener();
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
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void registerSharedPrefsListener() {
            //Log.d(LOG_TAG, " > > > > > > > registerSharedPrefsListener");
            PreferenceManager.getDefaultSharedPreferences(SunshineWatchFace.this)
                    .registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }

        private void unregisterSharedPrefsListener() {
            //Log.d(LOG_TAG, " < < < < < < < unregisterSharedPrefsListener");
            PreferenceManager.getDefaultSharedPreferences(SunshineWatchFace.this)
                    .unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }

        private SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener
                = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                //Log.d(LOG_TAG, "onSharedPreferenceChanged");
                getInfoFromSharedPrefs();
                postInvalidate();
            }
        };

        private void getInfoFromSharedPrefs(){
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(SunshineWatchFace.this);
            mHighTemp = sp.getString(DataLayerListenerService.LAST_HIGH_TEMP, "");
            mLowTemp = sp.getString(DataLayerListenerService.LAST_LOW_TEMP, "");
            mIcon = sp.getString(DataLayerListenerService.LAST_ICON, "800");
            mUpdated = "Updated: " + sp.getString(DataLayerListenerService.LAST_UPDATE, "");

            // try to avoid a bad weather ID
            if (mIcon.length() < 1) mIcon = "800";

            //Log.d(LOG_TAG, "getInfoFromSharedPrefs - Last Hi Temp: " + mHighTemp + "  Last Low Temp: " + mLowTemp + " Icon: " + mIcon);

        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

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
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    //Log.d(LOG_TAG, "onTapCommand: TAP_TYPE_TOUCH");
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    //Log.d(LOG_TAG, "onTapCommand: TAP_TYPE_TOUCH_CANCEL");
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    //Log.d(LOG_TAG, "onTapCommand: TAP_TYPE_TAP");
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onPeekCardPositionUpdate (Rect rect) {
            //Log.d(LOG_TAG, "onPeekCardPositionUpdate");
            postInvalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // find the center of the watchface
            int centerX = bounds.centerX();
            int centerY = bounds.centerY();

            // draw a little separator line
            int halfSeparatorWidth = bounds.width() / 6;
            canvas.drawLine(centerX - halfSeparatorWidth, centerY, centerX + halfSeparatorWidth, centerY, mUpdatedPaint);

            // draw the Date centered horizontally
            mDate = new Date();
            mDate.setTime(System.currentTimeMillis());

            // convert the date to a string according to the watchface_date_format
            String dateFormat = getString(R.string.watchface_date_format);
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormat);
            String shortDate = sdf.format(mDate).toUpperCase();

            // use the width of the date string to center it on the display
            float dateWidth = mDatePaint.measureText(shortDate);
            int dateOffset = (bounds.width() - Math.round(dateWidth)) / 2;

            // get the height of the date text
            Rect textBounds = new Rect();
            mDatePaint.getTextBounds(shortDate, 0, shortDate.length(), textBounds);
            int dateTextHeight = textBounds.height();

            // draw the date 1.5 text heights above center
            canvas.drawText(shortDate, dateOffset, centerY - (1f * dateTextHeight), mDatePaint);


            // Draw the time as HH:MM for both ambient mode and interactive mode.
            String timeFormat = "%02d:%02d";
            mTime.setToNow();
            String timeText = String.format(timeFormat, mTime.hour, mTime.minute);

            // center the time string horizontally
            float timeWidth = mTextPaint.measureText(timeText);
            int timeOffset = (bounds.width() - Math.round(timeWidth)) / 2;

            canvas.drawText(timeText, timeOffset, centerY - (3f * dateTextHeight), mTextPaint);

            // weather info
            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {

                getIconResourceForWeatherCondition(Integer.valueOf(mIcon));

                String degreeSymbol = "" + (char) 0x00B0;
                String hiTemp = "  " + mHighTemp + degreeSymbol;
                String loTemp = "  " + mLowTemp + degreeSymbol;

                // get the height of the temperature text
                mTempPaint.getTextBounds(hiTemp, 0, hiTemp.length(), textBounds);
                int textHeight = textBounds.height();

                //Log.d(LOG_TAG, "onDraw - temperatures: " + hiTemp + " " + loTemp + "  textHeight: " + textHeight);

                float tempWidth = mTempPaint.measureText(hiTemp + loTemp);

                // calculate the left edge of the weather info row so that the icon plus temperatures are centered
                int weatherOffset = (bounds.width() - (mWeatherIcon.getWidth() + Math.round(tempWidth))) / 2;
                //float y = mYOffset + mLineHeight * 3;
                float y = centerY + (dateTextHeight / 2);

                // center the weather icon vertically with the temperature text
                float textCenter = y + (mWeatherIcon.getHeight() / 2) + (textHeight / 2);
                //float iconTop = textCenter - mWeatherIcon.getHeight() / 2;

                canvas.drawBitmap(mWeatherIcon, weatherOffset, y, mDatePaint);
                canvas.drawText(hiTemp + loTemp, weatherOffset + mWeatherIcon.getWidth(), textCenter, mTempPaint);

                mUpdated = mUpdated.toUpperCase();
                tempWidth = mUpdatedPaint.measureText(mUpdated);
                // calculate the left edge of the weather info row so that the icon plus temperatures are centered
                weatherOffset = (bounds.width() - Math.round(tempWidth)) / 2;
                canvas.drawText(mUpdated, weatherOffset, textCenter + (1.5f * textHeight), mUpdatedPaint);
            }
        }


        /**
         * Helper method to provide the icon resource id according to the weather condition id returned
         * by the OpenWeatherMap call.
         * @param weatherId from OpenWeatherMap API response
         *
         */
        public void getIconResourceForWeatherCondition(int weatherId) {

            // only load the bitmap if it has changed
            if (weatherId == mPng){
                return;
            }
            mPng = weatherId;

            int ic_id = R.drawable.ic_clear;

            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                ic_id = R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                ic_id = R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                ic_id = R.drawable.ic_rain;
            } else if (weatherId == 511) {
                ic_id = R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                ic_id = R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                ic_id = R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                ic_id = R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                ic_id = R.drawable.ic_storm;
            } else if (weatherId == 800) {
                ic_id = R.drawable.ic_clear;
            } else if (weatherId == 801) {
                ic_id = R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                ic_id = R.drawable.ic_cloudy;
            }

            mWeatherIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(), ic_id);
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
    }
}
