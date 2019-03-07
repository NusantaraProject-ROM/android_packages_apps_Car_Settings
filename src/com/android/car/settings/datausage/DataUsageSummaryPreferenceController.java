/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.settings.datausage;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.net.NetworkTemplate;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.AbsoluteSizeSpan;
import android.util.RecurrenceRule;

import com.android.car.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.PreferenceController;
import com.android.car.settings.common.ProgressBarPreference;
import com.android.car.settings.network.NetworkUtils;
import com.android.settingslib.net.DataUsageController;

import java.util.concurrent.TimeUnit;

/**
 * Business logic for setting the {@link ProgressBarPreference} with the current data usage and the
 * appropriate summary text.
 */
public class DataUsageSummaryPreferenceController extends
        PreferenceController<ProgressBarPreference> {

    private static final long MILLIS_IN_A_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long MILLIS_IN_A_SECOND = TimeUnit.SECONDS.toMillis(1);
    private static final int MAX_PROGRESS_BAR_VALUE = 1000;

    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;
    private final DataUsageController mDataUsageController;
    private final NetworkTemplate mDefaultTemplate;

    /** The size of the first registered plan if one exists. -1 if no information is available. */
    private long mDataplanSize = -1;
    /**
     * Limit to track. Size of the first registered plan if one exists. Otherwise size of data limit
     * or warning.
     */
    private long mDataplanTrackingThreshold;
    /** The number of bytes used since the start of the cycle. */
    private long mDataplanUse;
    /** The ending time of the billing cycle in ms since the epoch */
    private long mCycleEnd;

    public DataUsageSummaryPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mDataUsageController = new DataUsageController(context);

        int defaultSubId = DataUsageUtils.getDefaultSubscriptionId(mSubscriptionManager);
        mDefaultTemplate = DataUsageUtils.getMobileNetworkTemplate(mTelephonyManager, defaultSubId);
    }

    @Override
    protected Class<ProgressBarPreference> getPreferenceType() {
        return ProgressBarPreference.class;
    }

    @Override
    protected int getAvailabilityStatus() {
        return NetworkUtils.hasSim(mTelephonyManager) ? AVAILABLE : CONDITIONALLY_UNAVAILABLE;
    }

    @Override
    protected void onCreateInternal() {
        getPreference().setMin(0);
        getPreference().setMax(MAX_PROGRESS_BAR_VALUE);
    }

    @Override
    protected void updateState(ProgressBarPreference preference) {
        DataUsageController.DataUsageInfo info = mDataUsageController.getDataUsageInfo(
                mDefaultTemplate);

        if (mSubscriptionManager != null) {
            refreshDataplanInfo(info);
        }

        preference.setTitle(getUsageText());
        preference.setSummary(getSummary(getLimitText(info), getRemainingBillingCycleTimeText()));

        if (mDataplanTrackingThreshold <= 0) {
            return;
        }

        preference.setMinLabel(DataUsageUtils.bytesToIecUnits(getContext(), /* byteValue= */ 0));
        preference.setMaxLabel(
                DataUsageUtils.bytesToIecUnits(getContext(), mDataplanTrackingThreshold));
        preference.setProgress(scaleUsage(mDataplanUse, mDataplanTrackingThreshold));
    }

    private CharSequence getUsageText() {
        Formatter.BytesResult usedResult = Formatter.formatBytes(getContext().getResources(),
                mDataplanUse, Formatter.FLAG_CALCULATE_ROUNDED | Formatter.FLAG_IEC_UNITS);
        SpannableString usageNumberText = new SpannableString(usedResult.value);
        int textSize = getContext().getResources().getDimensionPixelSize(
                R.dimen.usage_number_text_size);

        // Set the usage text (only the number) to the size defined by usage_number_text_size.
        usageNumberText.setSpan(new AbsoluteSizeSpan(textSize), /* start= */ 0,
                usageNumberText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        CharSequence template = getContext().getText(R.string.data_used_formatted);
        CharSequence usageText = TextUtils.expandTemplate(template, usageNumberText,
                usedResult.units);
        return usageText;
    }


    private CharSequence getLimitText(DataUsageController.DataUsageInfo info) {
        if (info.warningLevel > 0 && info.limitLevel > 0) {
            return TextUtils.expandTemplate(
                    getContext().getText(R.string.cell_data_warning_and_limit),
                    DataUsageUtils.bytesToIecUnits(getContext(), info.warningLevel),
                    DataUsageUtils.bytesToIecUnits(getContext(), info.limitLevel));
        } else if (info.warningLevel > 0) {
            return TextUtils.expandTemplate(getContext().getText(R.string.cell_data_warning),
                    DataUsageUtils.bytesToIecUnits(getContext(), info.warningLevel));
        } else if (info.limitLevel > 0) {
            return TextUtils.expandTemplate(getContext().getText(R.string.cell_data_limit),
                    DataUsageUtils.bytesToIecUnits(getContext(), info.limitLevel));
        }

        return null;
    }

    private CharSequence getRemainingBillingCycleTimeText() {
        long millisLeft = mCycleEnd - System.currentTimeMillis();
        if (millisLeft <= 0) {
            return getContext().getString(R.string.billing_cycle_none_left);
        } else {
            int daysLeft = (int) (millisLeft / MILLIS_IN_A_DAY);
            return daysLeft < 1
                    ? getContext().getString(R.string.billing_cycle_less_than_one_day_left)
                    : getContext().getResources().getQuantityString(
                            R.plurals.billing_cycle_days_left, daysLeft, daysLeft);
        }
    }

    private CharSequence getSummary(CharSequence dataLimitText, CharSequence cycleTimeText) {
        StringBuilder builder = new StringBuilder();

        boolean hasPrev = false;
        if (!TextUtils.isEmpty(dataLimitText)) {
            builder.append(dataLimitText);
            hasPrev = true;
        }

        if (!TextUtils.isEmpty(cycleTimeText)) {
            if (hasPrev) {
                builder.append("\n");
            }
            builder.append(cycleTimeText);
        }

        return builder.toString();
    }

    private void refreshDataplanInfo(DataUsageController.DataUsageInfo info) {
        // Reset data before overwriting.
        mDataplanSize = -1L;
        mDataplanTrackingThreshold = getSummaryLimit(info);
        mDataplanUse = info.usageLevel;
        mCycleEnd = info.cycleEnd;

        int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
        SubscriptionInfo subInfo = mSubscriptionManager.getDefaultDataSubscriptionInfo();
        if (subInfo != null) {
            SubscriptionPlan primaryPlan = DataUsageUtils.getPrimaryPlan(mSubscriptionManager,
                    defaultSubId);
            if (primaryPlan != null) {
                mDataplanSize = primaryPlan.getDataLimitBytes();
                if (mDataplanSize == SubscriptionPlan.BYTES_UNLIMITED) {
                    mDataplanSize = -1L;
                }
                mDataplanTrackingThreshold = mDataplanSize;
                mDataplanUse = primaryPlan.getDataUsageBytes();

                RecurrenceRule rule = primaryPlan.getCycleRule();
                if (rule != null && rule.start != null && rule.end != null) {
                    mCycleEnd = rule.end.toEpochSecond() * MILLIS_IN_A_SECOND;
                }
            }
        }
    }

    /** Scales the current usage to be an integer between 0 and {@link #MAX_PROGRESS_BAR_VALUE}. */
    private int scaleUsage(long usage, long maxUsage) {
        return (int) ((usage / (float) maxUsage) * MAX_PROGRESS_BAR_VALUE);
    }

    /**
     * Gets the max displayed limit based on {@link DataUsageController.DataUsageInfo}.
     *
     * @return the most appropriate limit for the data usage summary. Use the total usage when it
     * is higher than the limit and warning level. Use the limit when it is set and less than usage.
     * Otherwise use warning level.
     */
    private static long getSummaryLimit(DataUsageController.DataUsageInfo info) {
        long limit = info.limitLevel;
        if (limit <= 0) {
            limit = info.warningLevel;
        }
        if (info.usageLevel > limit) {
            limit = info.usageLevel;
        }
        return limit;
    }
}
