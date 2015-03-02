package com.testbed.peaclab.thermalprofiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Scanner;

import android.util.Log;

public class ThermalManagement extends Thread {

  private static final String TAG = "ThermalManagement";

  // file handles to manage CPU cores
  private static final String[] CPU_ENABLE_FILENAMES = {
    "/sys/devices/system/cpu/cpu0/online",
    "/sys/devices/system/cpu/cpu1/online",
    "/sys/devices/system/cpu/cpu2/online",
    "/sys/devices/system/cpu/cpu3/online"    
  };
  
  private static final String[] CPU_SET_FREQUENCY_FILENAMES = {
    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_setspeed",
    "/sys/devices/system/cpu/cpu1/cpufreq/scaling_setspeed",
    "/sys/devices/system/cpu/cpu2/cpufreq/scaling_setspeed",
    "/sys/devices/system/cpu/cpu3/cpufreq/scaling_setspeed"
  };
  
  private static final String[] CPU_GET_FREQUENCY_FILENAMES = {
    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
    "/sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq",
    "/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq",
    "/sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq"
  };

  
  //private static final float R_PCM_TO_AIR = 53.3f; // base assumptions
  //private static final float R_SI_TO_PCM = 0.0686f;
  
  private static final float R_PCM_TO_AIR = 10.267f; // units K/W. relates power going out of PCM
  private static final float R_SI_TO_PCM = 0.4060f; // units K/W. relates power going into PCM
  
  private static final float PCM_ENERGY_COOLDOWN_FRACTION = 0.10f;
  
  
  // temperature thresholds
  private static final float T_CPU_CRITICAL = 80.f;
  private static final float T_CPU_COOLDOWN = 70.f;
  
  // set the update interval, in milliseconds
  private static final int SAMPLING_INTERVAL_MS = 1000;
  private static final int THERMAL_CONTROL_DELAY_MS = 3000;
  

  private SensorRecorder mSensors;
  
  private volatile boolean mManagementEnabled;
  private long mTimeOfLastUpdate;
  
  private volatile boolean mTerminate;
  
  private TestbedTemperatures mCurrentTestbedTemperatures;
  private TestbedTemperatures mPreviousTestbedTemperatures;
  
  // policy fields
  private boolean mPolicy_Cooldown;
  
  // running avg of CPU temp.
  private int mNumCpuSamples;
  private int mCpuSampleIndex;
  private float[] mAvgCpuSamples;
  private static int CPU_TEMP_RUNNING_AVG_SAMPLES = 5;
  
  private long mCurrentTime, mPreviousTime;
  
  // keep track of active cores
  private volatile boolean[] mActiveCores;
  private volatile int[] mCoreFrequencies;
  
  public ThermalManagement(SensorRecorder sensors) {
    mSensors = sensors;
    
    mManagementEnabled = Executive.THERMAL_MANAGEMENT_ENABLED;
    mTimeOfLastUpdate = 0;
    
    mTerminate = false;
    
    mCurrentTestbedTemperatures = new TestbedTemperatures();
    mPreviousTestbedTemperatures = new TestbedTemperatures();
    
    mPolicy_Cooldown = false;
    
    mNumCpuSamples = 0;
    mCpuSampleIndex = 0;
    mAvgCpuSamples = new float[CPU_TEMP_RUNNING_AVG_SAMPLES];
    Arrays.fill(mAvgCpuSamples, 0.f);
    
    mCurrentTime = 0;
    mPreviousTime = 0;
    
    mActiveCores = new boolean[Testbed.TESTBED_NUM_CPU_CORES];
    mCoreFrequencies = new int[Testbed.TESTBED_NUM_CPU_CORES];
  }
  
  public void run() {
    float netPower = 0.f, pwrIn = 0.f, pwrOut = 0.f;
    float cpuTemperature = 0.f;
    float pcmTemperature = 0.f;
    float airTemperature = 0.f;
    
    long currentTime = System.currentTimeMillis();
    long previousTime = currentTime;
    long endTime = 0;
    long sleepTime = 0;
    float deltaTime = 0.f;
    
    Log.v(TAG, "Starting ThermalManagement thread");
    
    // sleep for a bit while the SensorRecorder thread starts sampling data
    try {
      Thread.sleep(1100);
    } catch (InterruptedException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    
    // check currently active cores and frequency settings
    try {
      checkActiveCores();
      checkCoreFrequencies();
    } catch (Throwable e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    
    // initialize time of thermal management update
    mTimeOfLastUpdate = System.currentTimeMillis();
    
    
    while (!mTerminate) {
      // timestamp
      currentTime = System.currentTimeMillis();
      deltaTime = (float)(currentTime - previousTime) / 1000.f;
      
      // get ambient air temperature
      airTemperature = mSensors.getAmbientTemperature();
      
      // get current testbed temperatures
      mCurrentTestbedTemperatures = mSensors.getCurrentTestbedTemperatures();
      pcmTemperature = mCurrentTestbedTemperatures.temperatureThermocouple;
      
      // calculate average core temperature
      cpuTemperature = (float)(
          mCurrentTestbedTemperatures.temperatureCore0 + 
          mCurrentTestbedTemperatures.temperatureCore1 + 
          mCurrentTestbedTemperatures.temperatureCore2 + 
          mCurrentTestbedTemperatures.temperatureCore3) / ((float)Testbed.TESTBED_NUM_CPU_CORES);
      
      // keep a running avg of CPU temperature
      //------------------------------------------------------------------------
      /*mAvgCpuSamples[mCpuSampleIndex++] = cpuTemperature;
      mCpuSampleIndex = (mCpuSampleIndex >= CPU_TEMP_RUNNING_AVG_SAMPLES) ? 0 : mCpuSampleIndex;
      mNumCpuSamples = (mNumCpuSamples >= CPU_TEMP_RUNNING_AVG_SAMPLES) ? CPU_TEMP_RUNNING_AVG_SAMPLES : mNumCpuSamples + 1;
      float cpuAvgTemp = 0.f;
      for (int i = 0; i < mNumCpuSamples; i++) {
        cpuAvgTemp += mAvgCpuSamples[i];
      }
      cpuAvgTemp = cpuAvgTemp / (float)mNumCpuSamples;*/

      // get PCM energy
      //------------------------------------------------------------------------
      float pcmEnergy = mCurrentTestbedTemperatures.energyPCM;
      
      /*
      Log.v(TAG, "CPU T=" + String.format("%.2f", cpuTemperature) + " (Av " + String.format("%.2f", cpuAvgTemp) + ")" +
          ", PCM T=" + String.format("%.2f", pcmTemperature) + 
          ", En=" + String.format("%5.2f", mPCMEnergy) + 
          ", Pwr=" + String.format("%5.2f", netPower) + " (" + String.format("%.3f", pwrIn) + " - " + String.format("%.3f", pwrOut) + ") (Av " + String.format("%.3f", powerAvg) + "), " +
          "Rsi=" + String.format("%.3f", mRsi) + ", Rpcm=" + String.format("%.3f", mRpcm));
      */
      
      
      // update thermal control. set a control delay, so that
      // any changes in active cores or frequency settings cannot
      // occur in quick succession.
      //------------------------------------------------------------------------
      if (mManagementEnabled && (currentTime > (mTimeOfLastUpdate + THERMAL_CONTROL_DELAY_MS))) {
        updateThermalManagement(cpuTemperature, pcmEnergy);
      }
      
      // wrap-up
      mPreviousTestbedTemperatures.copy(mCurrentTestbedTemperatures);
      previousTime = currentTime;
      
      endTime = System.currentTimeMillis();
      sleepTime = SAMPLING_INTERVAL_MS - (endTime - currentTime);
      sleepTime = (sleepTime < 0) ? 0 : sleepTime;
      
      // sleep
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }
    
    Log.v(TAG, "Terminated ThermalManagement thread");
  }
  
  private void updateThermalManagement(float cpuTemp, float pcmEnergy) {
    // THERMAL MANAGEMENT POLICY
    //----------------------------------
    //policy_turnOffCores(cpuTemp); // baseline, basic sprint
    //policy_throttleCores(cpuTemp); // baseline+, improved sprint
    //policy_throttleFrequency1(cpuTemp); // baseline++, temp dvfs
    
    policy_throttleFrequency2(pcmEnergy, cpuTemp, 0.75f); // pcm-aware
  }
  
  
  private void policy_turnOffCores(float cpuTemp) {
    final String tag = "policy_turnOffCores";
    
    if (cpuTemp > T_CPU_CRITICAL) {
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered (CPU T = " + String.format("%.1f", cpuTemp) + " C) Turning off cores ...");
      
      // deactivate all but one core
      for (int i = (Testbed.TESTBED_CPU_CORE_INDEX_MIN + 1); i <= Testbed.TESTBED_CPU_CORE_INDEX_MAX; i++) {
        if (!setCoreActive(i, false)) {
          Log.w(tag, "Error deactivating core " + i);
        }
      }

    }
  }
  
  private void policy_throttleCores(float cpuTemp) {
    String tag = "policy_throttleCores";
    
    if (cpuTemp > T_CPU_CRITICAL) {
      mPolicy_Cooldown = true;
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered CRIT (CPU T = " + String.format("%.1f", cpuTemp) + " C) Turning off cores ...");
      
      // deactivate all but one core
      for (int i = (Testbed.TESTBED_CPU_CORE_INDEX_MIN + 1); i <= Testbed.TESTBED_CPU_CORE_INDEX_MAX; i++) {
        if (!setCoreActive(i, false)) {
          Log.w(tag, "Error deactivating core " + i);
        }
      }
    }
    
    if (mPolicy_Cooldown && cpuTemp < T_CPU_COOLDOWN) {
      mPolicy_Cooldown = false;
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered COOL (CPU T = " + String.format("%.1f", cpuTemp) + " C) Turning on cores ...");
      
      // reactivate all cores
      for (int i = (Testbed.TESTBED_CPU_CORE_INDEX_MIN + 1); i <= Testbed.TESTBED_CPU_CORE_INDEX_MAX; i++) {
        if (!setCoreActive(i, true)) {
          Log.w(tag, "Error deactivating core " + i);
        }
      }
    }
  }
  
  private void policy_throttleFrequency1(float cpuTemp) {
    String tag = "policy_throttleFrequency1";
    
    if (cpuTemp > T_CPU_CRITICAL) {
      mPolicy_Cooldown = true;
      mTimeOfLastUpdate = System.currentTimeMillis() + 2000;
      Log.i(tag, "Policy triggered CRIT (CPU T = " + String.format("%.1f", cpuTemp) + " C) Throttle down cores ...");
      
      int throttleToFrequency = Testbed.freq2index(mCoreFrequencies[0]) - 1;
      throttleToFrequency = (throttleToFrequency < 0) ? 0 : throttleToFrequency;
      setCoreFrequencies(throttleToFrequency);
    }
    
    if (mPolicy_Cooldown && cpuTemp < T_CPU_COOLDOWN) {
      mPolicy_Cooldown = false;
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered COOL (CPU T = " + String.format("%.1f", cpuTemp) + " C) Throttle up cores ...");
      
      int throttleToFrequency = Testbed.FREQ_1242MHZ;
      setCoreFrequencies(throttleToFrequency);
    }
  }
  
  /* // deprecated
  private void policy_throttleFrequency1(float cpuTemp) {
    String tag = "policy_throttleFrequency1";
    
    if (cpuTemp > T_CPU_CRITICAL) {
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered (CPU T = " + String.format("%.1f", cpuTemp) + " C) Throttling cores ...");
      
      int throttleToFrequency = Testbed.FREQ_1026MHZ;
      setCoreFrequencies(throttleToFrequency);
    }
  }
  */
  
  private void policy_throttleFrequency2(float pcmEnergy, float cpuTemp, float pcmFraction) {
    String tag = "policy_throttleFrequency2";
    
    // to trigger this policy, pcmFraction is the fraction of total PCM that has been MELTED
    //
    if ( !mPolicy_Cooldown && (pcmEnergy > (SensorRecorder.PCM_ENERGY_MAX * pcmFraction)) ) {
      mPolicy_Cooldown = true;
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered CRIT (PCM E = " + String.format("%.1f", pcmEnergy) + " J) Throttling down cores ...");
      
      int throttleToFrequency = Testbed.freq2index(mCoreFrequencies[0]) - 1;
      throttleToFrequency = (throttleToFrequency < 0) ? 0 : throttleToFrequency;
      setCoreFrequencies(throttleToFrequency);
    }
    
    // also trigger policy if CPU temp reaches critical
    //
    if (cpuTemp > T_CPU_CRITICAL) {
      mTimeOfLastUpdate = System.currentTimeMillis() + 2000;
      Log.i(tag, "Policy triggered CRIT (CPU T = " + String.format("%.1f", cpuTemp) + " C) Throttle down cores ...");
      
      int throttleToFrequency = Testbed.freq2index(mCoreFrequencies[0]) - 1;
      throttleToFrequency = (throttleToFrequency < 0) ? 0 : throttleToFrequency;
      setCoreFrequencies(throttleToFrequency);
    }
    
    // throttle up cores if we recover PCM capacity
    //
    if (mPolicy_Cooldown && (pcmEnergy < (SensorRecorder.PCM_ENERGY_MAX * PCM_ENERGY_COOLDOWN_FRACTION)) ) {
      mPolicy_Cooldown = false;
      mTimeOfLastUpdate = System.currentTimeMillis();
      Log.i(tag, "Policy triggered COOL (CPU T = " + String.format("%.1f", pcmEnergy) + " C) Throttle up cores ...");
      
      int throttleToFrequency = Testbed.FREQ_1242MHZ;
      setCoreFrequencies(throttleToFrequency);
    }
  }
  
  public void checkActiveCores() {
    final String tag = "checkActiveCores";
    
    for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
      try {
        checkActiveCore(i);
      } catch (FileNotFoundException e) {
        Log.w(tag, "Core " + i + " FileNotFoundException");
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }
        
    Log.v(tag, "Cores Active: " + mActiveCores[0] + "," + mActiveCores[1] + "," + mActiveCores[2] + "," + mActiveCores[3]);
  }
  
  public void checkActiveCore(int core) throws FileNotFoundException {
    // use a Scanner to read the file
    Scanner sc = null;
    int cpuEnabledInt = 0;
    
    // attempt to read contents
    sc = new Scanner(new File(CPU_ENABLE_FILENAMES[core]));
    cpuEnabledInt = sc.nextInt();
    sc.close();
    
    mActiveCores[core] = (cpuEnabledInt > 0);
  }
  
  public void checkCoreFrequency(int core) throws FileNotFoundException {
    final String tag = "checkCoreFrequency";
    
    // check input args
    if (core < Testbed.TESTBED_CPU_CORE_INDEX_MIN || core > Testbed.TESTBED_CPU_CORE_INDEX_MAX) {
      Log.w(tag, "core argument (" + core + ") outside of allowed range [" + 
          Testbed.TESTBED_CPU_CORE_INDEX_MIN + "," + Testbed.TESTBED_CPU_CORE_INDEX_MAX + "]");
      return;
    }
    
    // use a Scanner to read the file
    Scanner sc = null;
    int cpuFrequencyInt = 0;
    
    // attempt to read CPU frequency, if the core is active
    if (mActiveCores[core]) {
      sc = new Scanner(new File(CPU_GET_FREQUENCY_FILENAMES[core]));
      cpuFrequencyInt = sc.nextInt();
      sc.close();
    } 
    
    // otherwise return 0
    else {
      cpuFrequencyInt = 0;
    }
    
    mCoreFrequencies[core] = cpuFrequencyInt;
  }
  
  public void checkCoreFrequencies() throws IllegalArgumentException, FileNotFoundException {
    for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
      checkCoreFrequency(i);
    }
    Log.v("checkCoreFrequencies", "Cores Freqs.: " + mCoreFrequencies[0] + "," + mCoreFrequencies[1] + "," + mCoreFrequencies[2] + "," + mCoreFrequencies[3]);
  }
  
  public boolean setCoreActive(int core, boolean enabled) {
    boolean success = false;
    final String tag = "setCoreActive";
    
    /*
     * NOTE: You should ensure the CPU file handles have
     * read/write permissions for ALL users. This may not
     * be the case, and only root can enable write
     * permissions (do this outside of this app).
     */
    
    // check input args
    if (core < Testbed.TESTBED_CPU_CORE_INDEX_MIN || core > Testbed.TESTBED_CPU_CORE_INDEX_MAX) {
      Log.w(tag, "core argument (" + core + ") outside of allowed range [" + 
          Testbed.TESTBED_CPU_CORE_INDEX_MIN + "," + Testbed.TESTBED_CPU_CORE_INDEX_MAX + "]");
      return success;
    }
    
    // write a 1 to active core, 0 to deactivate
    String coreSetting = enabled ? "1" : "0"; 
    byte[] writeBuffer = coreSetting.getBytes();
    
    // attempt to write
    try {
      FileOutputStream fout = new FileOutputStream(CPU_ENABLE_FILENAMES[core]);
      fout.write(writeBuffer);
      fout.flush();
      fout.close();
    } catch (IOException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }

    // check if the operation was a success. if we
    // can't check the state of the core, return false.
    try {
      checkActiveCore(core);
    } catch (FileNotFoundException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    success = (mActiveCores[core] == enabled);
    
    Log.v(tag, "Set core " + core + 
        " to " + (enabled ? "enabled" : "disabled") + 
        " (" + (success ? "success" : "failed") + 
        ", current setting = " + (mActiveCores[core] ? "enabled" : "disabled") + ")");
    
    return success;
  }
  
  public int getCoreFrequency(int core) {
    final String tag = "getCoreFrequency";
    
    if (core < Testbed.TESTBED_CPU_CORE_INDEX_MIN || core > Testbed.TESTBED_CPU_CORE_INDEX_MAX) {
      Log.w(tag, "core argument (" + core + ") outside of allowed range [" + 
          Testbed.TESTBED_CPU_CORE_INDEX_MIN + "," + Testbed.TESTBED_CPU_CORE_INDEX_MAX + "]");
      return -1;
    }
    
    return mCoreFrequencies[core];
  }
  
  
  public boolean setCoreFrequency(int core, int freqIndex) {
    boolean success = false;
    final String tag = "setCoreFrequency";
    
    /*
     * NOTE: You should ensure the CPU file handles have
     * read/write permissions for ALL users. This may not
     * be the case, and only root can enable write
     * permissions (do this outside of this app).
     */
    
    if (core < Testbed.TESTBED_CPU_CORE_INDEX_MIN || core > Testbed.TESTBED_CPU_CORE_INDEX_MAX) {
      Log.w(tag, "core argument (" + core + ") outside of allowed range [" + 
          Testbed.TESTBED_CPU_CORE_INDEX_MIN + "," + Testbed.TESTBED_CPU_CORE_INDEX_MAX + "]");
      return success;
    }
    if (freqIndex < Testbed.TESTBED_CPU_FREQ_INDEX_MIN || freqIndex > Testbed.TESTBED_CPU_FREQ_INDEX_MAX) {
      Log.w(tag, "freqIndex argument (" + freqIndex + ") outside of allowed range [" + 
          Testbed.TESTBED_CPU_FREQ_INDEX_MIN + "," + Testbed.TESTBED_CPU_FREQ_INDEX_MAX + "]");
      return success;
    }
    
    // write frequency value
    String freqSetting = String.format(Locale.US, "%d", Testbed.TESTBED_CPU_FREQUENCY[freqIndex]); 
    byte[] writeBuffer = freqSetting.getBytes();
    
    // attempt to write
    try {
      FileOutputStream fout = new FileOutputStream(CPU_SET_FREQUENCY_FILENAMES[core]);
      fout.write(writeBuffer);
      fout.flush();
      fout.close();
    } catch (IOException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    
    // update core frequency
    try {
      checkCoreFrequency(core);
    } catch (FileNotFoundException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    success = (mCoreFrequencies[core] == Testbed.TESTBED_CPU_FREQUENCY[freqIndex]);
    
    Log.v(tag, "Set core " + core + 
        " freq. to " + Testbed.TESTBED_CPU_FREQUENCY[freqIndex] + 
        " (" + (success ? "success" : "failed") + 
        ", current freq = " + mCoreFrequencies[core] + ")");
    
    return success;
  }
  
  public boolean setCoreFrequencies(int freqIndex) {
    boolean success = true;
    
    int i = 0;
    try {
      for (i = Testbed.TESTBED_CPU_CORE_INDEX_MIN; i <= Testbed.TESTBED_CPU_CORE_INDEX_MAX; i++) {
        if (mActiveCores[i]) {
          success = success && setCoreFrequency(i, freqIndex);
        }
      }
    } catch (Throwable e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    
    return success;
  }

  public synchronized boolean getManagementEnabled() {
    return mManagementEnabled;
  }

  public synchronized void setManagementEnabled(boolean enable) {
    this.mManagementEnabled = enable;
    mPolicy_Cooldown = false;
  }

  public synchronized boolean getTerminate() {
    return mTerminate;
  }

  public synchronized void setTerminate(boolean terminate) {
    this.mTerminate = terminate;
  }
  
  public synchronized void terminate() {
    mTerminate = true;
  }
  
}
