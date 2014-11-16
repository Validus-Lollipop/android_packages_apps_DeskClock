/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.deskclock.alarms;

import android.app.Profile;
import android.app.ProfileManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.android.deskclock.AlarmAlertWakeLock;
import com.android.deskclock.LogUtils;
import com.android.deskclock.provider.AlarmInstance;
import com.android.deskclock.R;

/**
 * This service is in charge of starting/stoping the alarm. It will bring up and manage the
 * {@link AlarmActivity} as well as {@link AlarmKlaxon}.
 */
public class AlarmService extends Service {
    // A public action send by AlarmService when the alarm has started.
    public static final String ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT";

    // A public action sent by AlarmService when the alarm has stopped for any reason.
    public static final String ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE";

    // Private action used to start an alarm with this service.
    public static final String START_ALARM_ACTION = "START_ALARM";

    // Private action used to stop an alarm with this service.
    public static final String STOP_ALARM_ACTION = "STOP_ALARM";

    /**
     * Utility method to help start alarm properly. If alarm is already firing, it
     * will mark it as missed and start the new one.
     *
     * @param context application context
     * @param instance to trigger alarm
     */
    public static void startAlarm(Context context, AlarmInstance instance) {
        Intent intent = AlarmInstance.createIntent(context, AlarmService.class, instance.mId);
        intent.setAction(START_ALARM_ACTION);

        // Maintain a cpu wake lock until the service can get it
        AlarmAlertWakeLock.acquireCpuWakeLock(context);
        context.startService(intent);
    }

    /**
     * Utility method to help stop an alarm properly. Nothing will happen, if alarm is not firing
     * or using a different instance.
     *
     * @param context application context
     * @param instance you are trying to stop
     */
    public static void stopAlarm(Context context, AlarmInstance instance) {
        Intent intent = AlarmInstance.createIntent(context, AlarmService.class, instance.mId);
        intent.setAction(STOP_ALARM_ACTION);

        // We don't need a wake lock here, since we are trying to kill an alarm
        context.startService(intent);
    }

    private TelephonyManager mTelephonyManager;
    private int mInitialCallState;
    private AlarmInstance mCurrentAlarm = null;

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String ignored) {
            // The user might already be in a call when the alarm fires. When
            // we register onCallStateChanged, we get the initial in-call state
            // which kills the alarm. Check against the initial call state so
            // we don't kill the alarm during a call.
            if (AlarmService.this.getResources().getBoolean(R.bool.config_silent_during_call)
                    && state != TelephonyManager.CALL_STATE_IDLE) {
                sendBroadcast(AlarmStateManager.createStateChangeIntent(AlarmService.this,
                        "AlarmService", mCurrentAlarm, AlarmInstance.MISSED_STATE));
            } else if (state != TelephonyManager.CALL_STATE_IDLE && state != mInitialCallState) {
                sendBroadcast(AlarmStateManager.createStateChangeIntent(AlarmService.this,
                        "AlarmService", mCurrentAlarm, AlarmInstance.MISSED_STATE));
                stopCurrentAlarm();
            }
        }
    };

    private void changeToProfile(final Context context, final AlarmInstance instance) {
        // The alarm is defined to change the active profile?
        if (instance.mProfile.equals(ProfileManager.NO_PROFILE)) {
            LogUtils.v("Alarm doesn't define a profile to change to");
            return;
        }

        final ProfileManager profileManager =
                (ProfileManager) context.getSystemService(Context.PROFILE_SERVICE);
        boolean isProfilesEnabled = Settings.System.getInt(context.getContentResolver(),
                Settings.System.SYSTEM_PROFILES_ENABLED, 1) == 1;
        if (!isProfilesEnabled) {
            LogUtils.v("Profiles are disabled");
            return;
        }

        // Ensure that the profile still exists
        Profile profile = profileManager.getProfile(instance.mProfile);
        if (profile == null) {
            LogUtils.e("The profile \"" + instance.mProfile
                    + "\" does not exist. Can't change to this profile");
            return;
        }

        // Is the current profile different?
        Profile activeProfile = profileManager.getActiveProfile();
        if (activeProfile == null || !profile.getUuid().equals(activeProfile.getUuid())) {
            // Change to profile
            LogUtils.i("Changing to profile \"" + profile.getName() + "\" (" + profile.getUuid()
                    + ") requested by alarm \"" + instance.mLabel + "\" (" + instance.mId + ")");
            profileManager.setActiveProfile(profile.getUuid());
        } else {
            LogUtils.v("The profile \"" + profile.getName() + "\" (" + profile.getUuid()
                    + " is already active. No need to change to");
        }
    }

    private void startAlarm(AlarmInstance instance) {
        LogUtils.v("AlarmService.start with instance: " + instance.mId);
        if (mCurrentAlarm != null) {
            AlarmStateManager.setMissedState(this, mCurrentAlarm);
            stopCurrentAlarm();
        }

        AlarmAlertWakeLock.acquireCpuWakeLock(this);
        mCurrentAlarm = instance;
        AlarmNotifications.showAlarmNotification(this, mCurrentAlarm);
        mInitialCallState = mTelephonyManager.getCallState();
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        boolean inCall = mInitialCallState != TelephonyManager.CALL_STATE_IDLE;
        AlarmKlaxon.start(this, mCurrentAlarm, inCall);
        changeToProfile(this, mCurrentAlarm);
        sendBroadcast(new Intent(ALARM_ALERT_ACTION));
    }

    private void stopCurrentAlarm() {
        if (mCurrentAlarm == null) {
            LogUtils.v("There is no current alarm to stop");
            return;
        }

        LogUtils.v("AlarmService.stop with instance: " + mCurrentAlarm.mId);
        AlarmKlaxon.stop(this);
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        sendBroadcast(new Intent(ALARM_DONE_ACTION));
        mCurrentAlarm = null;
        AlarmAlertWakeLock.releaseCpuLock();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtils.v("AlarmService.onStartCommand() with intent: " + intent.toString());

        long instanceId = AlarmInstance.getId(intent.getData());
        if (START_ALARM_ACTION.equals(intent.getAction())) {
            ContentResolver cr = this.getContentResolver();
            AlarmInstance instance = AlarmInstance.getInstance(cr, instanceId);
            if (instance == null) {
                LogUtils.e("No instance found to start alarm: " + instanceId);
                if (mCurrentAlarm != null) {
                    // Only release lock if we are not firing alarm
                    AlarmAlertWakeLock.releaseCpuLock();
                }
                return Service.START_NOT_STICKY;
            } else if (mCurrentAlarm != null && mCurrentAlarm.mId == instanceId) {
                LogUtils.e("Alarm already started for instance: " + instanceId);
                return Service.START_NOT_STICKY;
            }
            startAlarm(instance);
        } else if(STOP_ALARM_ACTION.equals(intent.getAction())) {
            if (mCurrentAlarm != null && mCurrentAlarm.mId != instanceId) {
                LogUtils.e("Can't stop alarm for instance: " + instanceId +
                        " because current alarm is: " + mCurrentAlarm.mId);
                return Service.START_NOT_STICKY;
            }
            stopSelf();
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        LogUtils.v("AlarmService.onDestroy() called");
        super.onDestroy();
        stopCurrentAlarm();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
