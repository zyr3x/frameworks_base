/*
 * Copyright (C) 2012 The ShenduOS Project
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

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;

import java.util.ArrayList;
import java.util.Collections;

/**
 * As stands, the RIL supports calling get subscription source which can
 * handle both ruim and nv qc devices. The lteOnCdma property allows
 * for the devices to provision for multiple cdma dun types at run time. This
 * extension of shared merely fixes nuance issues arising from the differences
 * caused by embedded SIM and legacy CDMA.
 * {@hide}
 */

public class HwCdmaQcomRIL extends HwQcomRIL implements CommandsInterface {
    boolean RILJ_LOGV = true;
    boolean RILJ_LOGD = true;
    private Object mSMSLock = new Object();
    private boolean mIsSendingSMS = false;
    public static final long SEND_SMS_TIMEOUT_IN_MS = 30000;

    public HwCdmaQcomRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
    }

    @Override
    public void
    sendCdmaSms(byte[] pdu, Message result) {
        // Do not send a new SMS until the response for the previous SMS has been received
        //   * for the error case where the response never comes back, time out after
        //     30 seconds and just try the next CDMA_SEND_SMS
        synchronized (mSMSLock) {
            long timeoutTime  = SystemClock.elapsedRealtime() + SEND_SMS_TIMEOUT_IN_MS;
            long waitTimeLeft = SEND_SMS_TIMEOUT_IN_MS;
            while (mIsSendingSMS && (waitTimeLeft > 0)) {
                Log.d(LOG_TAG, "sendCdmaSms() waiting for response of previous CDMA_SEND_SMS");
                try {
                    mSMSLock.wait(waitTimeLeft);
                } catch (InterruptedException ex) {
                    // ignore the interrupt and rewait for the remainder
                }
                waitTimeLeft = timeoutTime - SystemClock.elapsedRealtime();
            }
            if (waitTimeLeft <= 0) {
                Log.e(LOG_TAG, "sendCdmaSms() timed out waiting for response of previous CDMA_SEND_SMS");
            }
            mIsSendingSMS = true;
        }

        super.sendCdmaSms(pdu, result);
    }

    @Override
    protected Object responseSignalStrength(Parcel p) {
/*
        response[0] gsmDbm
        response[1] gsmEcio
        response[2] cdmaDbm
        response[3] cdmaEcio
        response[4] EvdoDbm
        response[5] evdoEcio
        response[6] evdoSNR
        response[7] lteSignalStrength
        response[8] lteRsrp
        response[9] lteRsrq
        response[10] lteRssnr
        response[11] lteCqi
*/
        int numInts = 12;
        int response[];

        response = new int[numInts];
        for (int i = 0 ; i < numInts ; i++) {
            response[i] = p.readInt();
        }

        if (response[7] == 99) {
            // If LTE is not enabled, clear LTE results
            // 7-11 must be -1 for GSM signal strength to be used (see
            // frameworks/base/telephony/java/android/telephony/SignalStrength.java)
            response[7] = -1;
            response[8] = -1;
            response[9] = -1;
            response[10] = -1;
            response[11] = -1;
        } else {
            response[8] *= -1;
        }
        return response;
    }

    // Workaround for China CDMA "ring of death" bug:
    //
    // Symptom: As soon as the phone receives notice of an incoming call, an
    // audible "old fashioned ring" is emitted through the earpiece and
    // persists through the duration of the call, or until reboot if the call
    // isn't answered.
    //
    // Background: The CDMA telephony stack implements a number of "signal info
    // tones" that are locally generated by ToneGenerator and mixed into the
    // voice call path in response to radio RIL_UNSOL_CDMA_INFO_REC requests.
    // One of these tones, IS95_CONST_IR_SIG_IS54B_L, is requested by the
    // radio just prior to notice of an incoming call when the voice call
    // path is muted. CallNotifier is responsible for stopping all signal
    // tones (by "playing" the TONE_CDMA_SIGNAL_OFF tone) upon receipt of a
    // "new ringing connection", prior to unmuting the voice call path.
    //
    // Problem: CallNotifier's incoming call path is designed to minimize
    // latency to notify users of incoming calls ASAP. Thus,
    // SignalInfoTonePlayer requests are handled asynchronously by spawning a
    // one-shot thread for each. Unfortunately the ToneGenerator API does
    // not provide a mechanism to specify an ordering on requests, and thus,
    // unexpected thread interleaving may result in ToneGenerator processing
    // them in the opposite order that CallNotifier intended. In this case,
    // playing the "signal off" tone first, followed by playing the "old
    // fashioned ring" indefinitely.
    //
    // Solution: An API change to ToneGenerator is required to enable
    // SignalInfoTonePlayer to impose an ordering on requests (i.e., drop any
    // request that's older than the most recent observed). Such a change,
    // or another appropriate fix should be implemented in AOSP first.
    //
    // Workaround: Intercept RIL_UNSOL_CDMA_INFO_REC requests from the radio,
    // check for a signal info record matching IS95_CONST_IR_SIG_IS54B_L, and
    // drop it so it's never seen by CallNotifier. If other signal tones are
    // observed to cause this problem, they should be dropped here as well.
    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;

        if (infoRec.record instanceof CdmaSignalInfoRec) {
            CdmaSignalInfoRec sir = (CdmaSignalInfoRec) infoRec.record;
            if (sir != null
                    && sir.isPresent
                    && sir.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && sir.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && sir.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {

                Log.d(LOG_TAG, "Dropping \"" + responseToString(response) + " "
                        + retToString(response, sir)
                        + "\" to prevent \"ring of death\" bug.");
                return;
            }
        }

        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    @Override
    protected Object
    responseSMS(Parcel p) {
        // Notify that sendSMS() can send the next SMS
        synchronized (mSMSLock) {
            mIsSendingSMS = false;
            mSMSLock.notify();
        }

        return super.responseSMS(p);
    }
}
