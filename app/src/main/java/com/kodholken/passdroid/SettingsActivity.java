/*
    This file is part of the Passdroid password management software.

    Copyright (C) 2009-2012  Magnus Eriksson <eriksson.mag@gmail.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.kodholken.passdroid;

import com.kodholken.passdroid.R;
import com.kodholken.passdroid.db.SystemData;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;

public class SettingsActivity extends TimeoutPreferenceActivity
                              implements OnSharedPreferenceChangeListener {
    private CheckBoxPreference biometricPreference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);

        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        updateSummaries();

        findPreference("change_password").setIntent(
                new Intent(this, ChangePasswordActivity.class));
        findPreference("import_export").setIntent(
                new Intent(this, ImportExportActivity.class));

        biometricPreference = (CheckBoxPreference) findPreference("biometric_unlock");
        biometricPreference.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if ((Boolean) newValue) {
                    // The preference is checked when the user has
                    // authenticated, see enableBiometricUnlock().
                    enableBiometricUnlock();
                    return false;
                }

                BiometricUnlock.disable(SettingsActivity.this);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Biometric unlock may have been turned off, e.g. after the
        // master password was changed.
        boolean enabled = BiometricUnlock.isEnabled(this);
        boolean available = BiometricUnlock.isAvailable(this);

        biometricPreference.setChecked(enabled);
        biometricPreference.setEnabled(enabled || available);
        biometricPreference.setSummary(enabled || available ?
                                       R.string.biometric_setting_summary :
                                       R.string.biometric_not_available);
    }

    private void enableBiometricUnlock() {
        if (MasterKey.isLegacy(new SystemData(this).getKey())) {
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.biometric_enable_upgrade_first));
            return;
        }

        BiometricUnlock.enable(this, Session.getInstance().getKey(),
                               new BiometricUnlock.Listener() {
            @Override
            public void onSuccess(byte [] key) {
                if (!isFinishing() && !isDestroyed()) {
                    biometricPreference.setChecked(true);
                }
            }

            @Override
            public void onFailure(String message) {
                if (message != null && !isFinishing() && !isDestroyed()) {
                    Utils.alertDialog(SettingsActivity.this,
                                      getString(R.string.failure), message);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updateSummaries();
        Session.getInstance().setNeedReload(true);

        if (ThemeSetting.PREFERENCE.equals(key)) {
            recreate();
        }
    }

    public void updateSummaries() {}
}
