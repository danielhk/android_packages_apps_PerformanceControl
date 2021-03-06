/*
 * Performance Control - An Android CPU Control application Copyright (C) 2012
 * James Roberts
 * Mali & Tegra3 CPU support (http://github.com/danielhk) 2016/2/24
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.brewcrewfoo.performance.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.brewcrewfoo.performance.R;
import com.brewcrewfoo.performance.fragments.VoltageControlSettings;
import com.brewcrewfoo.performance.fragments.Gpu;
import com.brewcrewfoo.performance.fragments.Tegra3;
import com.brewcrewfoo.performance.util.Constants;
import com.brewcrewfoo.performance.util.Helpers;
import com.brewcrewfoo.performance.util.Voltage;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class BootService extends Service implements Constants {
    public static boolean servicesStarted = false;
    Context context;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        context = this;
        if (intent == null) {
            stopSelf();
        }
        new BootWorker(this).execute();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class BootWorker extends AsyncTask<Void, Void, Void> {
        Context c;

        public BootWorker(Context c) {
            this.c = c;
        }

        @SuppressWarnings("deprecation")
        @Override
        protected Void doInBackground(Void... args) {

            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(c);

            // clear saved offsets - they make no sense after a reboot
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(PREF_OFFSETS, "").commit();

            final StringBuilder sb = new StringBuilder();
            final String FASTCHARGE_PATH = Helpers.fastcharge_path();
            final String BLN_PATH = Helpers.bln_path();
            final String gov = preferences.getString(PREF_GOV, Helpers.readOneLine(GOVERNOR_PATH));

	    String TOOLBOX = preferences.getString("TOOLBOX", "none");

	    if (TOOLBOX.equals("none")) {
		TOOLBOX = Helpers.checkBusybox() ? Helpers.getTOOLBOX() : "toybox";
	    }

	    if (!c.getResources().getBoolean(R.bool.config_use_initd)) {
		// DT2W
		if (preferences.getBoolean(PREF_DOUBLE_TAB_BOOT, false)) {
		    if (preferences.getBoolean(PREF_DOUBLE_TAB_WAKE, false)) {
			sb.append(TOOLBOX + " echo 1 > " + DOUBLE_TAB_WAKE_PATH + ";\n");
		    } else {
			sb.append(TOOLBOX + " echo 0 > " + DOUBLE_TAB_WAKE_PATH + ";\n");
		    }
		    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
			PREF_DOUBLE_TAB_DELTA, Integer.parseInt(	// get from preference
			Helpers.readOneLine(DOUBLE_TAB_DELTA_PATH))))	// default read from kernel
		        .append(" > ").append(DOUBLE_TAB_DELTA_PATH).append(";\n");
		    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
			PREF_DOUBLE_TAB_TIMEOUT, Integer.parseInt(
			Helpers.readOneLine(DOUBLE_TAB_TIMEOUT_PATH))))
			.append(" > ").append(DOUBLE_TAB_TIMEOUT_PATH).append(";\n");
		}
	    }

            if (preferences.getBoolean(CPU_SOB, false)) {
		// CPU Settings
                final String max = preferences.getString(
                        PREF_MAX_CPU, Helpers.readOneLine(MAX_FREQ_PATH));
                final String min = preferences.getString(
                        PREF_MIN_CPU, Helpers.readOneLine(MIN_FREQ_PATH));
                final String io = preferences.getString(PREF_IO, Helpers.getIOScheduler());

                for (int i = 0; i < Helpers.getNumOfCpus(); i++) {
                    sb.append(TOOLBOX + " echo ").append(max).append(" > ")
                            .append(MAX_FREQ_PATH.replace("cpu0", "cpu" + i)).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(min).append(" > ")
                            .append(MIN_FREQ_PATH.replace("cpu0", "cpu" + i)).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(gov).append(" > ")
                            .append(GOVERNOR_PATH.replace("cpu0", "cpu" + i)).append(";\n");
                }
                if (new File(TEGRA_MAX_FREQ_PATH).exists()) {
                    sb.append(TOOLBOX + " echo ").append(max).append(" > ")
                            .append(TEGRA_MAX_FREQ_PATH).append(";\n");
                }
                if (new File(DYN_MAX_FREQ_PATH).exists()) {
                    sb.append(TOOLBOX + " echo ").append(max).append(" > ")
                            .append(DYN_MAX_FREQ_PATH).append(";\n");
                }
                if (new File(DYN_MIN_FREQ_PATH).exists()) {
                    sb.append(TOOLBOX + " echo ").append(min).append(" > ")
                            .append(DYN_MIN_FREQ_PATH).append(";\n");
                }
                for (String aIO_SCHEDULER_PATH : IO_SCHEDULER_PATH) {
                    if (new File(aIO_SCHEDULER_PATH).exists())
                        sb.append(TOOLBOX + " echo ").append(io).append(" > ")
                                .append(aIO_SCHEDULER_PATH).append(";\n");
                }
            }
		//GPU vpll, default is 0
		/* in PC_DEFAULTS now
            if (!preferences.getBoolean(GPU_VPLL_PREF, false)) {
                sb.append(TOOLBOX + " echo 0").append(" > ").append(MALI_VPLL_FILE).append(";\n");
            }
	    else {
                sb.append(TOOLBOX + " echo 1").append(" > ").append(MALI_VPLL_FILE).append(";\n");
            }*/
            if (preferences.getBoolean(SOB_PREF, false)) {
		// Mali GPU setup
		String minFrequency = preferences.getString(FREQ_MIN_PREF, null);
		String maxFrequency = preferences.getString(FREQ_MAX_PREF, null);
		String minFreqVolt = preferences.getString(MIN_FREQ_VOLT_PREF, null);
		String maxFreqVolt = preferences.getString(MAX_FREQ_VOLT_PREF, null);
		String currentFrequencies[] = Helpers.readOneLine(CUR_FREQ_FILE).split(" ");
		String currentVoltages[] = Helpers.readOneLine(CUR_VOLT_FILE).split(" ");
		String availableFrequenciesLine[] = c.getResources().getStringArray(R.array.gpu_max_freq_values);
		String availableVoltagesLine[] = c.getResources().getStringArray(R.array.gpu_max_voltage_values);
		boolean noFreqSettings = ((availableFrequenciesLine == null) ||
		                     (minFrequency == null) && (maxFrequency == null));
		boolean noVoltSettings = ((availableVoltagesLine == null) ||
		                     (minFreqVolt == null) && (maxFreqVolt == null));

		List<String> voltages = null;
		if (noVoltSettings) {
		    Log.d(TAG, "No GPU voltage settings saved. Nothing to restore.");
		} else {
		    voltages = Arrays.asList(availableVoltagesLine);
		    if (voltages != null && voltages.contains(maxFreqVolt)) {
			currentVoltages[1] = maxFreqVolt;
		    }
		    availableVoltagesLine = c.getResources().getStringArray(R.array.gpu_min_voltage_values);
		    if (availableVoltagesLine != null){
			voltages = Arrays.asList(availableVoltagesLine);
		    } else {
			voltages = null;
		    }
		    if (voltages != null && voltages.contains(minFreqVolt)) {
			currentVoltages[0] = minFreqVolt;
		    }
                    sb.append(TOOLBOX + " echo ").append(currentVoltages[0])
                            .append(" ").append(currentVoltages[1])
                            .append(" > ").append(CUR_VOLT_FILE).append(";\n");
		    Log.d(TAG, "GPU voltage settings restored.");
		}

		List<String> frequencies = null;
		if (noFreqSettings) {
		    Log.d(TAG, "No GPU clock settings saved. Nothing to restore.");
		} else {
		    frequencies = Arrays.asList(availableFrequenciesLine);
		    if (frequencies != null && frequencies.contains(maxFrequency)) {
			currentFrequencies[1] = maxFrequency;
		    }
		    availableFrequenciesLine = c.getResources().getStringArray(R.array.gpu_min_freq_values);
		    if (availableFrequenciesLine != null){
			frequencies = Arrays.asList(availableFrequenciesLine);
		    } else {
			frequencies = null;
		    }
		    if (frequencies != null && frequencies.contains(minFrequency)) {
			currentFrequencies[0] = minFrequency;
		    }
                    sb.append(TOOLBOX + " echo ").append(currentFrequencies[0])
                            .append(" ").append(currentFrequencies[1])
                            .append(" > ").append(CUR_FREQ_FILE).append(";\n");
		    Log.d(TAG, "GPU clock settings restored.");
		}
	    }
            if (preferences.getBoolean(VOLTAGE_SOB, false)) {
		// CPU Voltage Settings
                if (Helpers.voltageFileExists()) {
                    final List<Voltage> volts = VoltageControlSettings.getVolts(preferences);
                    if (Helpers.getVoltagePath().equals(VDD_PATH)) {
                        for (final Voltage volt : volts) {
                            if (!volt.getSavedMV().equals(volt.getCurrentMv())) {
                                for (int i = 0; i < Helpers.getNumOfCpus(); i++) {
                                    sb.append(TOOLBOX + " echo ").append(volt.getFreq())
                                            .append(" ").append(volt.getSavedMV())
                                            .append(" > ").append(Helpers.getVoltagePath()
                                            .replace("cpu0", "cpu" + i)).append(";\n");
                                }
                            }
                        }
                    } else {
                        //other formats
                        final StringBuilder b = new StringBuilder();
                        for (final Voltage volt : volts) {
                            b.append(volt.getSavedMV()).append(" ");
                        }
                        for (int i = 0; i < Helpers.getNumOfCpus(); i++) {
                            sb.append(TOOLBOX + " echo ").append(b.toString()).append(" > ")
                                    .append(Helpers.getVoltagePath()
                                            .replace("cpu0", "cpu" + i)).append(";\n");
                        }
                    }
                }
            }
	    // Tegra3 Settings
            if (preferences.getBoolean(PREF_BACKLIGHT_SOB, false)) {
                final String min = preferences.getString(PREF_MIN_BACKLIGHT,
                        		Helpers.readOneLine(TEGRA_MIN_BACKLIGHT_PATH));
		sb.append(TOOLBOX + " echo ").append(min).append(" > ")
		  .append(TEGRA_MIN_BACKLIGHT_PATH).append(";\n");
                final String max = preferences.getString(PREF_MAX_BACKLIGHT,
                        		Helpers.readOneLine(TEGRA_MAX_BACKLIGHT_PATH));
		sb.append(TOOLBOX + " echo ").append(max).append(" > ")
		  .append(TEGRA_MAX_BACKLIGHT_PATH).append(";\n");
            }
            if (preferences.getBoolean(PREF_GPU_MAX_SOB, false)) {
                final String value = preferences.getString(PREF_GPU_MAX_FREQ,
                        		Helpers.readOneLine(TEGRA_GPU_MAX_FREQ_PATH));
		sb.append(TOOLBOX + " echo ").append(value).append(" > ")
		  .append(TEGRA_GPU_MAX_FREQ_PATH).append(";\n");
            }
            if (preferences.getBoolean(PREF_GPU_VOLT_SOB, false)) {
		final List<Voltage> volts = Tegra3.readVoltages(TEGRA_GPU_UV_MV_PATH);
		sb.append(TOOLBOX + " echo ");		// header
		for (int i=0;i< Tegra3.MAX_VOLTAGE_PREF;i++) {
		    String value = preferences.getString(PREF_GPU_UV_MV+Integer.toString(i),
                        		volts.get(i).getCurrentMv());
		    volts.get(i).setCurrentMV(value);
		    sb.append(value).append(" ");
		}
		sb.append("> ").append(TEGRA_GPU_UV_MV_PATH).append(";\n");
            }
            if (preferences.getBoolean(PREF_LP_VOLT_SOB, false)) {
		final List<Voltage> volts = Tegra3.readVoltages(TEGRA_LP_UV_MV_PATH);
		sb.append(TOOLBOX + " echo ");		// header
		for (int i=0;i< Tegra3.MAX_VOLTAGE_PREF;i++) {
		    String value = preferences.getString(PREF_LP_UV_MV+Integer.toString(i),
                        		volts.get(i).getCurrentMv());
		    volts.get(i).setCurrentMV(value);
		    sb.append(value).append(" ");
		}
		sb.append("> ").append(TEGRA_LP_UV_MV_PATH).append(";\n");
            }
            if (preferences.getBoolean(PREF_EMC_VOLT_SOB, false)) {
		final List<Voltage> volts = Tegra3.readVoltages(TEGRA_EMC_UV_MV_PATH);
		sb.append(TOOLBOX + " echo ");		// header
		for (int i=0;i< Tegra3.MAX_VOLTAGE_PREF;i++) {
		    String value = preferences.getString(PREF_EMC_UV_MV+Integer.toString(i),
                        		volts.get(i).getCurrentMv());
		    volts.get(i).setCurrentMV(value);
		    sb.append(value).append(" ");
		}
		sb.append("> ").append(TEGRA_EMC_UV_MV_PATH).append(";\n");
            }
	    // Other Settings
            if (preferences.getBoolean(PREF_READ_AHEAD_BOOT, false)) {
                final String values = preferences.getString(
                        PREF_READ_AHEAD, Helpers.readOneLine(READ_AHEAD_PATH));
                if (new File(READ_AHEAD_PATH).exists())
                    sb.append(TOOLBOX + " echo ").append(values).append(" > ")
                            .append(READ_AHEAD_PATH).append(";\n");
            }
            if (FASTCHARGE_PATH != null) {
                if (preferences.getBoolean(PREF_FASTCHARGE, false)) {
                    sb.append(TOOLBOX + " echo 1 > ").append(FASTCHARGE_PATH).append(";\n");
                    Intent i = new Intent();
                    i.setAction(INTENT_ACTION_FASTCHARGE);
                    c.sendBroadcast(i);
                    // add notification to warn user they can only charge
                    CharSequence contentTitle = c.getText(R.string.fast_charge_notification_title);
                    CharSequence contentText = c.getText(R.string.fast_charge_notification_message);

                    Notification n = new Notification.Builder(c)
                            .setAutoCancel(true).setContentTitle(contentTitle)
                            .setContentText(contentText)
                            .setSmallIcon(R.drawable.ic_launcher)
                            .setWhen(System.currentTimeMillis()).getNotification();

                    NotificationManager nm = (NotificationManager) getApplicationContext()
                            .getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(1337, n);
                }
            }
            if (new File(BLX_PATH).exists()) {
                if (preferences.getBoolean(BLX_SOB, false)) {
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_BLX, Integer.parseInt(Helpers.readOneLine(BLX_PATH))))
                            .append(" > ").append(BLX_PATH).append(";\n");
                }
            }
            if (new File(DSYNC_PATH).exists()) {
                if (preferences.getBoolean(PREF_DSYNC, false)) {
                    sb.append(TOOLBOX + " echo 1 > " + DSYNC_PATH + ";\n");
                } else {
                    sb.append(TOOLBOX + " echo 0 > " + DSYNC_PATH + ";\n");
                }
            }
            if (new File(BL_TIMEOUT_PATH).exists()) {
                if (preferences.getBoolean(BLTIMEOUT_SOB, false)) {
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_BLTIMEOUT, Integer.parseInt(Helpers.readOneLine(BL_TIMEOUT_PATH))))
                            .append(" > ").append(BL_TIMEOUT_PATH).append(";\n");
                }
            }
            if (new File(BL_TOUCH_ON_PATH).exists()) {
                if (preferences.getBoolean(PREF_BLTOUCH, false)) {
                    sb.append(TOOLBOX + " echo 1 > " + BL_TOUCH_ON_PATH + ";\n");
                } else {
                    sb.append(TOOLBOX + " echo 0 > " + BL_TOUCH_ON_PATH + ";\n");
                }
            }
            if (BLN_PATH != null) {
                if (preferences.getBoolean(PREF_BLN, false)) {
                    sb.append(TOOLBOX + " echo 1 > ").append(BLN_PATH).append(";\n");
                } else {
                    sb.append(TOOLBOX + " echo 0 > ").append(BLN_PATH).append(";\n");
                }
            }
            if (new File(PFK_HOME_ENABLED).exists() && new File(PFK_MENUBACK_ENABLED).exists()) {
                if (preferences.getBoolean(PFK_SOB, false)) {
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_HOME_ALLOWED_IRQ, Integer.parseInt(
                            Helpers.readOneLine(PFK_HOME_ALLOWED_IRQ)))).append(" > ")
                            .append(PFK_HOME_ALLOWED_IRQ).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_HOME_REPORT_WAIT, Integer.parseInt(
                            Helpers.readOneLine(PFK_HOME_REPORT_WAIT)))).append(" > ")
                            .append(PFK_HOME_REPORT_WAIT).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_MENUBACK_INTERRUPT_CHECKS, Integer.parseInt(
                            Helpers.readOneLine(PFK_MENUBACK_INTERRUPT_CHECKS))))
                            .append(" > ").append(PFK_MENUBACK_INTERRUPT_CHECKS).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_MENUBACK_FIRST_ERR_WAIT, Integer.parseInt(
                            Helpers.readOneLine(PFK_MENUBACK_FIRST_ERR_WAIT))))
                            .append(" > ").append(PFK_MENUBACK_FIRST_ERR_WAIT).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_MENUBACK_LAST_ERR_WAIT, Integer.parseInt(
                            Helpers.readOneLine(PFK_MENUBACK_LAST_ERR_WAIT)))).append(" > ")
                            .append(PFK_MENUBACK_LAST_ERR_WAIT).append(";\n");
                    if (preferences.getBoolean(PFK_HOME_ON, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + PFK_HOME_ENABLED + ";\n");
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + PFK_HOME_ENABLED + ";\n");
                    }
                    if (preferences.getBoolean(PFK_MENUBACK_ON, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + PFK_MENUBACK_ENABLED + ";\n");
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + PFK_MENUBACK_ENABLED + ";\n");
                    }
                }
            }
            boolean isdynamic = false;
            if (new File(DYNAMIC_DIRTY_WRITEBACK_PATH).exists()) {
                if (preferences.getBoolean(DYNAMIC_DIRTY_WRITEBACK_SOB, false)) {
                    if (preferences.getBoolean(PREF_DYNAMIC_DIRTY_WRITEBACK, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + DYNAMIC_DIRTY_WRITEBACK_PATH + ";\n");
                        isdynamic = true;
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + DYNAMIC_DIRTY_WRITEBACK_PATH + ";\n");
                    }
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_DIRTY_WRITEBACK_ACTIVE, Integer.parseInt(
                            Helpers.readOneLine(DIRTY_WRITEBACK_ACTIVE_PATH))))
                            .append(" > ").append(DIRTY_WRITEBACK_ACTIVE_PATH).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_DIRTY_WRITEBACK_SUSPEND, Integer.parseInt(
                            Helpers.readOneLine(DIRTY_WRITEBACK_SUSPEND_PATH))))
                            .append(" > ").append(DIRTY_WRITEBACK_SUSPEND_PATH).append(";\n");
                }
            }

            if (preferences.getBoolean(VM_SOB, false)) {
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_DIRTY_RATIO, Integer.parseInt(
                        Helpers.readOneLine(DIRTY_RATIO_PATH)))).append(" > ")
                        .append(DIRTY_RATIO_PATH).append(";\n");
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_DIRTY_BACKGROUND, Integer.parseInt(
                        Helpers.readOneLine(DIRTY_BACKGROUND_PATH)))).append(" > ")
                        .append(DIRTY_BACKGROUND_PATH).append(";\n");
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_DIRTY_EXPIRE, Integer.parseInt(
                        Helpers.readOneLine(DIRTY_EXPIRE_PATH)))).append(" > ")
                        .append(DIRTY_EXPIRE_PATH).append(";\n");
                if (!isdynamic) {
                    sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                            PREF_DIRTY_WRITEBACK, Integer.parseInt(
                            Helpers.readOneLine(DIRTY_WRITEBACK_PATH)))).append(" > ")
                            .append(DIRTY_WRITEBACK_PATH).append(";\n");
                }
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_MIN_FREE_KB, Integer.parseInt(
                        Helpers.readOneLine(MIN_FREE_PATH)))).append(" > ")
                        .append(MIN_FREE_PATH).append(";\n");
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_OVERCOMMIT, Integer.parseInt(
                        Helpers.readOneLine(OVERCOMMIT_PATH)))).append(" > ")
                        .append(OVERCOMMIT_PATH).append(";\n");
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_SWAPPINESS, Integer.parseInt(
                        Helpers.readOneLine(SWAPPINESS_PATH)))).append(" > ")
                        .append(SWAPPINESS_PATH).append(";\n");
                sb.append(TOOLBOX + " echo ").append(preferences.getInt(
                        PREF_VFS, Integer.parseInt(
                        Helpers.readOneLine(VFS_CACHE_PRESSURE_PATH)))).append(" > ")
                        .append(VFS_CACHE_PRESSURE_PATH).append(";\n");
            }
            if (preferences.getBoolean(PREF_MINFREE_BOOT, false)) {
                sb.append(TOOLBOX + " echo ").append(preferences.getString(
                        PREF_MINFREE, Helpers.readOneLine(MINFREE_PATH))).append(" > ")
                        .append(MINFREE_PATH).append(";\n");
            }
            if (new File(USER_PROC_PATH).exists()) {
                if (preferences.getBoolean(USER_PROC_SOB, false)) {
                    if (preferences.getBoolean(PREF_USER_PROC, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + USER_PROC_PATH + ";\n");
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + USER_PROC_PATH + ";\n");
                    }
                    sb.append(TOOLBOX + " echo ").append(preferences.getString(
                            PREF_USER_NAMES, Helpers.readOneLine(USER_PROC_NAMES_PATH)))
                            .append(" > ").append(USER_PROC_NAMES_PATH).append(";\n");
                }
            }
            if (new File(SYS_PROC_PATH).exists()) {
                if (preferences.getBoolean(SYS_PROC_SOB, false)) {
                    if (preferences.getBoolean(PREF_SYS_PROC, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + SYS_PROC_PATH + ";\n");
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + SYS_PROC_PATH + ";\n");
                    }
                    sb.append(TOOLBOX + " echo ").append(preferences.getString(
                            PREF_SYS_NAMES, Helpers.readOneLine(USER_SYS_NAMES_PATH)))
                            .append(" > ").append(USER_SYS_NAMES_PATH).append(";\n");
                }
            }
            if (new File(KSM_RUN_PATH).exists()) {
                if (preferences.getBoolean(KSM_SOB, false)) {
                    if (preferences.getBoolean(PREF_RUN_KSM, false)) {
                        sb.append(TOOLBOX + " echo 1 > " + KSM_RUN_PATH + ";\n");
                    } else {
                        sb.append(TOOLBOX + " echo 0 > " + KSM_RUN_PATH + ";\n");
                    }
                    sb.append(TOOLBOX + " echo ").append(preferences.getString(
                            "pref_ksm_pagetoscan", Helpers.readOneLine(KSM_PAGESTOSCAN_PATH)))
                            .append(" > ").append(KSM_PAGESTOSCAN_PATH).append(";\n");
                    sb.append(TOOLBOX + " echo ").append(preferences.getString(
                            "pref_ksm_sleep", Helpers.readOneLine(KSM_SLEEP_PATH)))
                            .append(" > ").append(KSM_SLEEP_PATH).append(";\n");
                }
            }
            if (preferences.getBoolean(GOV_SOB, false)) {
                final String gn = preferences.getString(GOV_NAME, "");
                if (gn.equals(gov)) {
                    final String gs = preferences.getString(GOV_SETTINGS, null);
                    if (gs != null) {
                        String p[] = gs.split(";");
                        for (String aP : p) {
                            final String pn[] = aP.split(":");
                            sb.append(TOOLBOX + " echo ").append(pn[1]).append(" > ")
                                    .append(GOV_SETTINGS_PATH).append(gov).append("/")
                                    .append(pn[0]).append(";\n");
                        }
                    }
                }
            }
            sb.append(preferences.getString(PREF_SH, "# no custom shell command")).append(";\n");
            Helpers.shExec(sb, context, true);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            Helpers.updateAppWidget(c);
            super.onPostExecute(result);
            servicesStarted = true;
            stopSelf();
        }
    }
}
