/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.car.settings.common;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.car.settings.R;
import com.android.car.settings.quicksettings.QuickSettingFragment;

/**
 * Base activity class for car settings, provides a action bar with a back button that goes to
 * previous activity.
 */
public class CarSettingActivity extends AppCompatActivity implements
        BaseFragment.FragmentController {
    private static final String TAG = "CarSetting";

    private CarUxRestrictionsHelper mUxRestrictionsHelper;
    private CarUxRestrictions mCarUxRestrictions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_compat_activity);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mUxRestrictionsHelper == null) {
            mUxRestrictionsHelper =
                    new CarUxRestrictionsHelper(this, carUxRestrictions -> {
                        mCarUxRestrictions = carUxRestrictions;
                        BaseFragment currentFragment = getCurrentFragment();
                        if (currentFragment != null) {
                            currentFragment.setCarUxRestrictions(carUxRestrictions);
                        }
                    });
        }

        mUxRestrictionsHelper.start();

        if (getCurrentFragment() == null) {
            launchFragment(QuickSettingFragment.newInstance());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mUxRestrictionsHelper.stop();
    }

    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    public void launchFragment(BaseFragment fragment) {
        if (mCarUxRestrictions != null && !fragment.canBeShown(mCarUxRestrictions)) {
            DoBlockingDialogFragment alertDialog = new DoBlockingDialogFragment();
            alertDialog.show(getSupportFragmentManager(), DoBlockingDialogFragment.DIALOG_TAG);
            return;
        }
        if (mCarUxRestrictions != null) {
            fragment.setCarUxRestrictions(mCarUxRestrictions);
        }
        fragment.setFragmentController(this);
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.animator.trans_right_in ,
                        R.animator.trans_left_out,
                        R.animator.trans_left_in,
                        R.animator.trans_right_out)
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void goBack() {
        onBackPressed();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        hideKeyboard();
        // if the backstack is empty, finish the activity.
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            finish();
        }
    }

    private BaseFragment getCurrentFragment() {
        return (BaseFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)this.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }
}
