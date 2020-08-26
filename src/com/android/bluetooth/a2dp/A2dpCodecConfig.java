/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.bluetooth.a2dp;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.util.Log;

import com.android.bluetooth.R;

import java.util.Arrays;
import java.util.Objects;
/*
 * A2DP Codec Configuration setup.
 */
class A2dpCodecConfig {
    private static final boolean DBG = true;
    private static final String TAG = "A2dpCodecConfig";

    private Context mContext;
    private A2dpNativeInterface mA2dpNativeInterface;

    private BluetoothCodecConfig[] mCodecConfigPriorities;

    A2dpCodecConfig(Context context, A2dpNativeInterface a2dpNativeInterface) {
        mContext = context;
        mA2dpNativeInterface = a2dpNativeInterface;
        mCodecConfigPriorities = assignCodecConfigPriorities();
    }

    BluetoothCodecConfig[] codecConfigPriorities() {
        return mCodecConfigPriorities;
    }

    void setCodecConfigPreference(BluetoothDevice device,
                                  BluetoothCodecStatus codecStatus,
                                  BluetoothCodecConfig newCodecConfig) {
        Objects.requireNonNull(codecStatus);

        // Check whether the codecConfig is selectable for this Bluetooth device.
        BluetoothCodecConfig[] selectableCodecs = codecStatus.getCodecsSelectableCapabilities();
        if (!Arrays.asList(selectableCodecs).stream().anyMatch(codec ->
                codec.isMandatoryCodec())) {
            // Do not set codec preference to native if the selectableCodecs not contain mandatory
            // codec. The reason could be remote codec negotiation is not completed yet.
            Log.w(TAG, "setCodecConfigPreference: must have mandatory codec before changing.");
            return;
        }
        if (!codecStatus.isCodecConfigSelectable(newCodecConfig)) {
            Log.w(TAG, "setCodecConfigPreference: invalid codec "
                    + Objects.toString(newCodecConfig));
            return;
        }

        // Check whether the codecConfig would change current codec config.
        int prioritizedCodecType = getPrioitizedCodecType(newCodecConfig, selectableCodecs);
        BluetoothCodecConfig currentCodecConfig = codecStatus.getCodecConfig();
        if (prioritizedCodecType == currentCodecConfig.getCodecType()
                && (prioritizedCodecType != newCodecConfig.getCodecType()
                || (currentCodecConfig.similarCodecFeedingParameters(newCodecConfig)
                && currentCodecConfig.sameCodecSpecificParameters(newCodecConfig)))) {
            // Same codec with same parameters, no need to send this request to native.
            Log.w(TAG, "setCodecConfigPreference: codec not changed.");
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = new BluetoothCodecConfig[1];
        codecConfigArray[0] = newCodecConfig;
        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    void enableOptionalCodecs(BluetoothDevice device, BluetoothCodecConfig currentCodecConfig) {
        if (currentCodecConfig != null && !currentCodecConfig.isMandatoryCodec()) {
            Log.i(TAG, "enableOptionalCodecs: already using optional codec "
                    + currentCodecConfig.getCodecName());
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = assignCodecConfigPriorities();
        if (codecConfigArray == null) {
            return;
        }

        // Set the mandatory codec's priority to default, and remove the rest
        for (int i = 0; i < codecConfigArray.length; i++) {
            BluetoothCodecConfig codecConfig = codecConfigArray[i];
            if (codecConfig != null && !codecConfig.isMandatoryCodec()) {
                codecConfigArray[i] = null;
            }
        }

        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    void disableOptionalCodecs(BluetoothDevice device, BluetoothCodecConfig currentCodecConfig) {
        if (currentCodecConfig != null && currentCodecConfig.isMandatoryCodec()) {
            Log.i(TAG, "disableOptionalCodecs: already using mandatory codec.");
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = assignCodecConfigPriorities();
        if (codecConfigArray == null) {
            return;
        }
        // Set the mandatory codec's priority to highest, and remove the rest
        for (int i = 0; i < codecConfigArray.length; i++) {
            BluetoothCodecConfig codecConfig = codecConfigArray[i];
            if (codecConfig != null && codecConfig.isMandatoryCodec()) {
                codecConfig.setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST);
            } else {
                codecConfigArray[i] = null;
            }
        }
        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    // Get the codec type of the highest priority of selectableCodecs and codecConfig.
    private int getPrioitizedCodecType(BluetoothCodecConfig codecConfig,
            BluetoothCodecConfig[] selectableCodecs) {
        BluetoothCodecConfig prioritizedCodecConfig = codecConfig;
        for (BluetoothCodecConfig config : selectableCodecs) {
            if (prioritizedCodecConfig == null) {
                prioritizedCodecConfig = config;
            }
            if (config.getCodecPriority() > prioritizedCodecConfig.getCodecPriority()) {
                prioritizedCodecConfig = config;
            }
        }
        return prioritizedCodecConfig.getCodecType();
    }

    // Assign the A2DP Source codec config priorities
    private BluetoothCodecConfig[] assignCodecConfigPriorities() {
        Resources resources = mContext.getResources();
        if (resources == null) {
            return null;
        }

        int[] priorities = new int[BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX];
        Arrays.fill(priorities, BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT);

        int value;
        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_sbc);
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            priorities[BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC] = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aac);
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            priorities[BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC] = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aptx);
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            priorities[BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX] = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aptx_hd);
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            priorities[BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD] = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_ldac);
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            priorities[BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC] = value;
        }

        BluetoothCodecConfig[] codecConfigArray =
                new BluetoothCodecConfig[BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX];
        for (int i = 0; i < codecConfigArray.length; i++) {
            codecConfigArray[i] = new BluetoothCodecConfig(i,
                    priorities[i], BluetoothCodecConfig.SAMPLE_RATE_NONE,
                    BluetoothCodecConfig.BITS_PER_SAMPLE_NONE,
                    BluetoothCodecConfig.CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                    0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        }

        return codecConfigArray;
    }
}

