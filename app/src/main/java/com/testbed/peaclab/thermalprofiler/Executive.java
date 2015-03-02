package com.testbed.peaclab.thermalprofiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import jnt.scimark2.Constants;
import jnt.scimark2.LU;
import jnt.scimark2.Random;
import jnt.scimark2.SOR;
import jnt.scimark2.SparseCompRow;
import jnt.scimark2.kernel;


public class Executive extends Activity implements SeekBar.OnSeekBarChangeListener {

  /* ***********************************************************************/
  // RUN-TIME OPTIONS
  //
  // These options will modify the behavior of the app (for lack of GUI elements
  // to set these kinds of options).
  /* ***********************************************************************/

  // Set true to enable timed benchmarks: When you press the Benchmark button,
  // the benchmark will run for a fixed amount of time, and CANNOT be interrupted.
  // In this mode, the benchmark will start IDLE_DELAY_MS milliseconds after
  // pressing the Benchmark button.
  //
  // Set to false to enable continuous benchmark: When you press the Benchmark
  // button, the benchmark will run forever, until you press the Benchmark button
  // again, to interrupt and stop the benchmark.
  private static final boolean TIMED_BENCHMARK = true;


  // Set to true to allow the Benchmark thread to set the frequency of the CPU
  // core before executing the benchmark.
  private static final boolean BENCHMARK_SETS_FREQUENCY = false;


  // Set to true to allow the ThermalManagement thread to employ a thermal
  // management policy.
  public static final boolean THERMAL_MANAGEMENT_ENABLED = false;


  /*
   * TO SET THE THERMAL MANAGEMENT POLICY TO USE:
   * see the updateThermalManagement() function in the ThermalManagement class.
   *
   */


  // Select which SciMark benchmark application to run:
  //
  //  SOR     Successive Over Relaxation
  //  SMULT   Sparse Multiplication
  //  LU      Lower-Upper Factorization
  //
  private static final Benchmark BENCHMARK_APP = Benchmark.SOR;


  // Set true to enable debug messages to appear in the app's on-screen Debug Log.
  // Most informational messages should be controlled by this flag. Only
  // important (or unexpected) warnings and messages should appear in the log
  // regardless of this flag setting.
  private static final boolean ENABLE_GUI_DEBUG = false;
  //
  // Animating and displaying any graphical elements on-screen cause significant
  // (albeit very brief) spikes in the power consumption. That's why the GUI should
  // be kept as un-animated and plain as possible to minimize "power overhead"
  // while recording benchmarks.



  /* ***********************************************************************/
  // TYPES
  /* ***********************************************************************/

  // enum for benchmark application type
  public enum Benchmark {
    SOR, SMULT, LU
  }


  /* ***********************************************************************/
  // CONSTANTS
  /* ***********************************************************************/

  // filename to store ambient temperature setting
  private static final String AMBIENT_TEMP_FILENAME = "ambient.txt";
  private static final float DEFAULT_AMBIENT_TEMP = 22.f;

  // USB Device constants
  private static final String ACTION_USB_PERMISSION = "com.testbed.thermalprofiler.USB_PERMISSION";

  // intent strings
  private static final String ACTION_TPROF_COMMAND = "com.testbed.peaclab.action.TPROF_COMMAND";

  // fields for the Agilent U1252A USB device
  private static final int AGILENT_U1252A_VENDOR_ID = 0x067B;
  private static final int AGILENT_U1252A_PRODUCT_ID = 0x2303;

  private EnumMap<Benchmark, String> BENCHMARK_STRING;


  /* ***********************************************************************/
  // MEMBERS
  /* ***********************************************************************/

  private SensorRecorder mSensorRecorderThread;
  private ThermalManagement mThermalManagementThread;

  private BenchmarkRunner[] asyncBenchmarks = new BenchmarkRunner[4];
  private boolean[] asyncBenchmarkRunning;

  private ArrayList<TimeInterval> latestBenchmarkTimes;

  // USB device fields
  private UsbManager mUsbManager;
  private ArrayList<UsbDevice> mUsbDevices = new ArrayList<UsbDevice>();

  // this USB device corresponds to the Agilent U1252A multimeter, which
  // uses a Prolific serial port interface.
  private UsbDevice mAgilentDevice = null;
  private boolean mAgilentDevicePermission = false;

  /* ***********************************************************************/
  // BROADCAST INTENT RECEIVERS
  /* ***********************************************************************/

  /**
   * Receives broadcast when a supported USB device is attached, detached or
   * when a permission to communicate to the device has been granted.
   */
  private PendingIntent mUsbPermissionIntent;
  private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
      String deviceName = usbDevice.getDeviceName();

      synchronized (mUsbDevices) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
          if (usbDevice != null) {
            mUsbDevices.add(usbDevice);
            //debugLogMessage("USB Device \"" + deviceName + "\" attached.");

            if (usbDevice.getVendorId() == AGILENT_U1252A_VENDOR_ID &&
                    usbDevice.getProductId() == AGILENT_U1252A_PRODUCT_ID) {
              mAgilentDevice = usbDevice;
              mSensorRecorderThread.setAgilentDevice(mAgilentDevice);
            }

          } else {
            Log.e("usbReceiver.onReceive", "USB device is not initialized");
          }

        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
          if (usbDevice != null) {
            mUsbDevices.remove(usbDevice);
            debugLogMessage("USB Device \"" + deviceName + "\" detached.");

            if (usbDevice.getVendorId() == AGILENT_U1252A_VENDOR_ID &&
                    usbDevice.getProductId() == AGILENT_U1252A_PRODUCT_ID) {
              mAgilentDevice = null;
              mSensorRecorderThread.setAgilentDevice(null);
            }

          } else {
            Log.e("usbReceiver.onReceive", "USB device is not initialized");
          }

        } else if (ACTION_USB_PERMISSION.equals(action)) {
          boolean permission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
          //debugLogMessage("USB Device \"" + deviceName + "\" permissions: " + permission);

          if (usbDevice.getVendorId() == AGILENT_U1252A_VENDOR_ID &&
                  usbDevice.getProductId() == AGILENT_U1252A_PRODUCT_ID) {
            mAgilentDevicePermission = permission;
          }
        }
      }
    }
  };

  /**
   * Receives broadcast when a ThermalProfiler command intent is received.
   */
  private PendingIntent mCommandIntent;
  private final BroadcastReceiver mCommandReceiver = new BroadcastReceiver() {
    private final String TPROF_EXTRA_KEY_COMMAND = "command";
    private final String TPROF_EXTRA_KEY_AMBIENT = "ambient";
    private final String TPROF_EXTRA_KEY_THREADS = "threads";

    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();

      if (action.equalsIgnoreCase(ACTION_TPROF_COMMAND)) {
        if (intent.hasExtra(TPROF_EXTRA_KEY_COMMAND)) {
          String command = intent.getStringExtra(TPROF_EXTRA_KEY_COMMAND);
          ToggleButton toggleButton;
          Button button;

          // handle toggling Record
          if (command.equalsIgnoreCase("record")) {
            toggleButton = (ToggleButton) findViewById(R.id.toggleButton_record);
            toggleButton.toggle();
            buttonRecord((View)toggleButton);
          }

          // handle toggling Benchmark
          else if (command.equalsIgnoreCase("benchmark")) {
            toggleButton = (ToggleButton) findViewById(R.id.toggleButton_benchmark);
            toggleButton.toggle();
            buttonBenchmark((View)toggleButton);
          }

          // handle pressing Ambient Temp (increase)
          else if (command.equalsIgnoreCase("ambient_inc")) {
            button = (Button) findViewById(R.id.button_ambientPlus);
            buttonAmbientPlus((View)button);
          }

          // handle pressing Ambient Temp (decrease)
          else if (command.equalsIgnoreCase("ambient_dec")) {
            button = (Button) findViewById(R.id.button_ambientMinus);
            buttonAmbientMinus((View)button);
          }

          // handle pressing Debug
          else if (command.equalsIgnoreCase("debug")) {
            button = (Button) findViewById(R.id.button_debugFn);
            debugFunction((View)button);
          }
        } // has TPROF_EXTRA_KEY_COMMAND

        if (intent.hasExtra(TPROF_EXTRA_KEY_AMBIENT)) {
          float ambientTemp = intent.getFloatExtra(TPROF_EXTRA_KEY_AMBIENT, DEFAULT_AMBIENT_TEMP);
          editTextAmbientSet(ambientTemp);
        } // has TPROF_EXTRA_KEY_AMBIENT

        if (intent.hasExtra(TPROF_EXTRA_KEY_THREADS)) {
          SeekBar seekBar_threads = (SeekBar) findViewById(R.id.seekBar_threads);
          int numThreads = intent.getIntExtra(TPROF_EXTRA_KEY_THREADS, Testbed.TESTBED_NUM_CPU_CORES_MIN);
          seekBar_threads.setProgress(numThreads);
        } // has TPROF_EXTRA_KEY_THREADS

      }

      //debugLogMessage("ACTION: " + action + ", EXTRA: " + command);
    }
  };

  /* ***********************************************************************/
  // ENTRY POINT
  /* ***********************************************************************/

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_executive);

    // set the formatted intro text
    Resources res = getResources();
    String text = String.format(res.getString(R.string.text_intro),
            res.getString(R.string.button_record_start),
            res.getString(R.string.button_benchmark_start));

    TextView textView_intro = (TextView) findViewById(R.id.textView_intro);
    textView_intro.setText(text);

    // disable on-screen keyboard
    this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

    SeekBar seekBar_cores = (SeekBar) findViewById(R.id.seekBar_threads);
    seekBar_cores.setOnSeekBarChangeListener(this);

    // Get UsbManager from Android.
    mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

    // handle USB intents
    mUsbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
    IntentFilter usbFilter = new IntentFilter();
    usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    usbFilter.addAction(ACTION_USB_PERMISSION);
    registerReceiver(mUsbReceiver, usbFilter);

    // handle ThermalProfiler command intents
    mCommandIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_TPROF_COMMAND), 0);
    IntentFilter cmdFilter = new IntentFilter();
    cmdFilter.addAction(ACTION_TPROF_COMMAND);
    registerReceiver(mCommandReceiver, cmdFilter);

    // initialize USB device list
    mUsbDevices.clear();
    for (UsbDevice device : mUsbManager.getDeviceList().values()) {
      //Log.d("onCreate", "Detected device: " + device.toString());
      //debugLogMessage("USB Device detected: " + device.toString());
      mUsbDevices.add(device);

      if (device.getVendorId() == AGILENT_U1252A_VENDOR_ID &&
              device.getProductId() == AGILENT_U1252A_PRODUCT_ID) {
        debugLogMessage("USB Agilent Device \"" + device.getDeviceName() + "\" attached.");
        mAgilentDevice = device;

        // request permission to communicate with the USB device.
        mUsbManager.requestPermission(mAgilentDevice, mUsbPermissionIntent);
      }
    }

    /**
     * TODO: enable intents to command the app to record and run benchmark
     */
    /* ****************************************************************** */
    /*
    if (Intent.ACTION_MAIN.equals(action) && type != null) {
      if (type.equalsIgnoreCase("record")) {
        debugLogMessage("UI: Received record intent!");
      } else if (type.equalsIgnoreCase("benchmark")) {
        debugLogMessage("UI: Received benchmark intent!");
      }
    }
    */
    /* ****************************************************************** */

    // init objects
    BENCHMARK_STRING = new EnumMap<Benchmark, String>(Benchmark.class);
    BENCHMARK_STRING.put(Benchmark.SOR, "SOR");
    BENCHMARK_STRING.put(Benchmark.SMULT, "SMULT");
    BENCHMARK_STRING.put(Benchmark.LU, "LU");

    // keep track of benchmark threads running
    asyncBenchmarkRunning = new boolean[Testbed.TESTBED_NUM_CPU_CORES];
    Arrays.fill(asyncBenchmarkRunning, false);

    latestBenchmarkTimes = new ArrayList<TimeInterval>(Testbed.TESTBED_NUM_CPU_CORES);
    for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
      latestBenchmarkTimes.add(new TimeInterval(0,0));
    }

    // read ambient temperature
    float ambientTemp = DEFAULT_AMBIENT_TEMP;
    try {
      ambientTemp = readAmbientTempFile();
    } catch (FileNotFoundException e) {
      Log.w("readAmbientTempFile", "No ambient temperature file found, setting temperature to default (" + String.format("%.1f", DEFAULT_AMBIENT_TEMP) + ")");
    }

    // display ambient
    EditText editText_ambient = (EditText) findViewById(R.id.editText_ambient);
    editText_ambient.setText(String.format("%.1f", ambientTemp));


    mSensorRecorderThread = new SensorRecorder(mUsbManager, ambientTemp);
    mSensorRecorderThread.setAgilentDevice(mAgilentDevice);
    mSensorRecorderThread.start();

    mThermalManagementThread = new ThermalManagement(mSensorRecorderThread);
    mThermalManagementThread.start();


    // show the current run-time settings
    debugLogMessage("Benchmark App: " + BENCHMARK_STRING.get(BENCHMARK_APP));
    debugLogMessage("Benchmark Mode: " + (TIMED_BENCHMARK ? "Timed" : "Continuous"));
    debugLogMessage("Benchmark Freq: " + (BENCHMARK_SETS_FREQUENCY ? "Set by benchmark" : "Unmodified by benchmark"));
    debugLogMessage("Thermal Management: " + (THERMAL_MANAGEMENT_ENABLED ? "Enabled" : "Disabled"));
  }

  @Override
  public void onResume() {
    super.onResume();

    mUsbDevices.clear();
    for (UsbDevice device : mUsbManager.getDeviceList().values()) {
      //Log.d("onCreate", "Detected device: " + device.toString());
      //debugLogMessage("USB Device detected: " + device.toString());
      mUsbDevices.add(device);

      if (device.getVendorId() == AGILENT_U1252A_VENDOR_ID &&
              device.getProductId() == AGILENT_U1252A_PRODUCT_ID) {
        debugLogMessage("USB Agilent Device \"" + device.getDeviceName() + "\" re-attached.");
        mAgilentDevice = device;
        mSensorRecorderThread.setAgilentDevice(mAgilentDevice);

        // request permission to communicate with the USB device.
        mUsbManager.requestPermission(mAgilentDevice, mUsbPermissionIntent);
      }
    }
  }

  @Override
  protected void onPause() {
    super.onPause();

    // save ambient temperature
    try {
      writeAmbientTempFile();
    } catch (IOException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    mThermalManagementThread.terminate();
    mSensorRecorderThread.terminate();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.menu_executive, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();

    //noinspection SimplifiableIfStatement
    if (id == R.id.action_settings) {
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  //
  // CALLBACK: CPU Cores Seek Bar
  //--------------------------------------------------------------------------
  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    // bounds check on number of selectable cores
    if (progress < Testbed.TESTBED_NUM_CPU_CORES_MIN) {
      progress = Testbed.TESTBED_NUM_CPU_CORES_MIN;
      seekBar.setProgress(progress);
    } else if (progress > Testbed.TESTBED_NUM_CPU_CORES_MAX) {
      progress = Testbed.TESTBED_NUM_CPU_CORES_MAX;
      seekBar.setProgress(progress);
    }

    // show the number of selected cores
    EditText editText_threads = (EditText) findViewById(R.id.editText_threads);
    editText_threads.setText(Integer.toString(progress));
  }


  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {
    // TODO Auto-generated method stub
  }


  @Override
  public void onStopTrackingTouch(SeekBar seekBar) {
    // TODO Auto-generated method stub
  }

  //
  // CALLBACK: Record Button
  //--------------------------------------------------------------------------
  public void buttonRecord(View view) {
    ToggleButton toggleButton_record = (ToggleButton) view;

    //
    // handle case when button is ON:
    // start recording
    if (toggleButton_record.isChecked()) {
      if (ENABLE_GUI_DEBUG)
        debugLogMessage("UI: Started recording!");

      mSensorRecorderThread.setRecordState(true);
    }

    //
    // handle case when button is OFF:
    // stop recording
    else {
      //asyncStatsRecorder.cancel(false);

      if (ENABLE_GUI_DEBUG)
        debugLogMessage("UI: Stopped recording!");

      mSensorRecorderThread.setBenchmarkTime(getBenchmarkTimeInterval());
      mSensorRecorderThread.setRecordState(false);
    }
  }

  //
  // CALLBACK: Benchmark Button
  //--------------------------------------------------------------------------
  public void buttonBenchmark(View view) {
    ToggleButton toggleButton_benchmark = (ToggleButton) view;
    SeekBar seekBar_threads = (SeekBar) findViewById(R.id.seekBar_threads);

    // check how many cores have been selected
    int numThreads = seekBar_threads.getProgress();

    //
    // handle case when button is ON:
    // start recording
    if (toggleButton_benchmark.isChecked()) {

      for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
        if (i < numThreads) {
          asyncBenchmarks[i] = new BenchmarkRunner();
          asyncBenchmarks[i].executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, i);
          asyncBenchmarkRunning[i] = true;
        } else {
          asyncBenchmarkRunning[i] = false;
        }

      }

      if (ENABLE_GUI_DEBUG)
        debugLogMessage("UI: Started benchmark!");

      // reset the recorded benchmark times
      for (TimeInterval t : latestBenchmarkTimes) {
        t.setTimes(0,0);
      }

      // turn on thermal management, if allowed
      mThermalManagementThread.setManagementEnabled(THERMAL_MANAGEMENT_ENABLED);
    }

    //
    // handle case when button is OFF:
    // stop recording
    else {
      for (int i = 0; i < asyncBenchmarks.length; i++) {
        if (asyncBenchmarks[i] != null) {
          asyncBenchmarks[i].cancel(false);
        }
      }

      if (ENABLE_GUI_DEBUG) {
        debugLogMessage("UI: Stopped benchmark!");
      }
    }
  }

  //
  // CALLBACK: Ambient Temp. PLUS Button
  //--------------------------------------------------------------------------
  public void buttonAmbientPlus(View view) {
    Button button_ambientPlus = (Button) view;

    float ambientTemp = mSensorRecorderThread.getAmbientTemperature() + 0.1f;
    ambientTemp = Math.round(ambientTemp * 10.f) * 0.1f;

    // display ambient
    editTextAmbientSet(ambientTemp);
  }

  //
  // CALLBACK: Ambient Temp. MINUS Button
  //--------------------------------------------------------------------------
  public void buttonAmbientMinus(View view) {
    Button button_ambientMinus = (Button) view;

    float ambientTemp = mSensorRecorderThread.getAmbientTemperature() - 0.1f;
    ambientTemp = Math.round(ambientTemp * 10.f) * 0.1f;

    // display ambient
    editTextAmbientSet(ambientTemp);
  }

  public void editTextAmbientSet(float ambientTemp) {
    EditText editText_ambient = (EditText) findViewById(R.id.editText_ambient);
    editText_ambient.setText(String.format("%.1f", ambientTemp));

    mSensorRecorderThread.setAmbientTemperature(ambientTemp);

    // save ambient temperature
    try {
      writeAmbientTempFile();
    } catch (IOException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
  }

  //
  // CALLBACK: Debug Function Button
  //--------------------------------------------------------------------------
  public void debugFunction(View view) {

    // use this function for anything you want to test out, maybe
    // print some infoz on the debug log.
    //
    // -- hints --
    // use this line in your catch blocks:
    //   Log.e(e.getClass().toString(), e.getMessage(), e);
    //

    if (true) {
      try {
        mThermalManagementThread.checkActiveCores();
        mThermalManagementThread.checkCoreFrequencies();

        for (int i = 0; i < 4; i++)
          debugLogMessage("Core " + i + ": " + mThermalManagementThread.getCoreFrequency(i));

      } catch (Throwable e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }

    if (false) {
      // set frequency
      try {
        boolean success = mThermalManagementThread.setCoreFrequency(0,8);
        if (!success)
          Log.w("BenchmarkRunner", "Error setting frequency to " + Testbed.TESTBED_CPU_FREQUENCY[8] + " MHz!");
      } catch (Throwable e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }

    // TEST: write ambient file
    if (false) {
      try {
        writeAmbientTempFile();
      } catch (IOException e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }


    // TEST: Serial Port -- single read
    if (false) {
      debugLogMessage("DBG: USB Permission = " + mAgilentDevicePermission);

      UsbSerialDriver serialDriver = new ProlificSerialDriver(mAgilentDevice);
      UsbDeviceConnection connection = mUsbManager.openDevice(mAgilentDevice);

      List<UsbSerialPort> serialPorts = serialDriver.getPorts();
      if (serialPorts.size() > 0) {
        byte[] buffer = new byte[128];
        Arrays.fill(buffer, (byte)0);
        UsbSerialPort serialPort = serialPorts.get(0);

        try {
          serialPort.open(connection);
          serialPort.setParameters(19200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

          boolean purged = serialPort.purgeHwBuffers(true,true);
          debugLogMessage("DBG: Purged = " + purged);

          int totalBytesRead = serialPort.read(buffer, 4000);
          String bufferContents = new String(buffer, "UTF-8");
          debugLogMessage("DBG: Read " + totalBytesRead + " bytes = " + bufferContents);

          serialPort.close();

        } catch (Throwable e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
          debugLogMessage("DBG: There was a problem with USB ... \"" + e.getMessage() + "\"");
        }
      }

    }

    // TEST: Serial Port
    if (false) {
      debugLogMessage("DBG: USB Permission = " + mAgilentDevicePermission);

      UsbSerialDriver serialDriver = new ProlificSerialDriver(mAgilentDevice);
      UsbDeviceConnection connection = mUsbManager.openDevice(mAgilentDevice);

      List<UsbSerialPort> serialPorts = serialDriver.getPorts();
      if (serialPorts.size() > 0) {
        byte[] buffer = new byte[30];
        int bufferIndex = 0;
        UsbSerialPort serialPort = serialPorts.get(0);

        try {
          serialPort.open(connection);
          serialPort.setParameters(19200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

          int totalBytesRead = 0;
          for (int i = 0; i < 30; i++) {
            byte[] byteReadBuffer = new byte[1];
            int bytesRead = serialPort.read(byteReadBuffer, 4000);
            totalBytesRead += bytesRead;
            if (bytesRead > 0) {
              buffer[bufferIndex++] = byteReadBuffer[0];
            } else {
              debugLogMessage("DBG: Finished reading at " + i + " (returned " + bytesRead + ")");
              break;
            }
          }

          serialPort.close();

          String bufferContents = "";
          String bufferContentsAscii = "";
          for (int i = 0; i < totalBytesRead; i++) {
            bufferContents += "0x" + String.format("%02X", buffer[i]) + " ";

            if (buffer[i] >= 0x20 && buffer[i] < 0x7F) {
              bufferContentsAscii += (char)buffer[i];
            } else {
              bufferContentsAscii += " (null) ";
            }
          }

          debugLogMessage("DBG: Bytes Read (" + totalBytesRead + ") = " + bufferContents + " (" + bufferContentsAscii + ")");

        } catch (Throwable e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
          debugLogMessage("DBG: There was a problem with USB ... \"" + e.getMessage() + "\"");
        }
      }

    }


    // TEST: reading process stats
    if (false) {
      RandomAccessFile statFile = null;
      try {
        statFile = new RandomAccessFile("/proc/stat", "r");

        for (int i = 0; i < 1; i++) {
          String line = statFile.readLine();
          debugLogMessage("DBG: " + line);

          String[] cpu_stats = line.split("[ ]+");
          debugLogMessage("DBG: line has " + cpu_stats.length + " tokens");

          for (int j = 0; j < cpu_stats.length; j++) {
            debugLogMessage("DBG: " + cpu_stats[j]);
          }
        }

        statFile.close();

      } catch (IOException e_io) {
        Log.e(e_io.getClass().toString(), e_io.getMessage(), e_io);
      }
    }

  }

  //
  // UTILITY FUNCTIONS
  //--------------------------------------------------------------------------
  private void debugLogMessage(String text) {
    EditText editText_debugLog = (EditText) findViewById(R.id.editText_debugLog);
    editText_debugLog.append("\n" + text);
  }

  private TimeInterval getBenchmarkTimeInterval() {
    TimeInterval benchmarkTime = new TimeInterval(0,0);
    long threadBenchmarkTime = 0;

    // find the earliest start time
    benchmarkTime.startTime = latestBenchmarkTimes.get(0).startTime;
    for (int i = 1; i < latestBenchmarkTimes.size(); i++) {
      threadBenchmarkTime = latestBenchmarkTimes.get(i).startTime;
      benchmarkTime.startTime = (threadBenchmarkTime > 0) ? Math.min(benchmarkTime.startTime, threadBenchmarkTime) : benchmarkTime.startTime;
    }

    // find the latest stop time
    benchmarkTime.stopTime = latestBenchmarkTimes.get(0).stopTime;
    for (int i = 1; i < latestBenchmarkTimes.size(); i++) {
      threadBenchmarkTime = latestBenchmarkTimes.get(i).stopTime;
      benchmarkTime.stopTime = (threadBenchmarkTime > 0) ? Math.max(benchmarkTime.stopTime, threadBenchmarkTime) : benchmarkTime.stopTime;
    }

    return benchmarkTime;
  }

  private float readAmbientTempFile() throws FileNotFoundException {
    File ambientFile = new File(this.getFilesDir(), AMBIENT_TEMP_FILENAME);
    float ambientTemp = 0.f;

    // attempt to read contents
    Scanner sc = new Scanner(ambientFile);
    try {
      ambientTemp = sc.nextFloat();
    } catch (NoSuchElementException e) {
      Log.w("readAmbientTempFile", "Invalid format! File \"" + AMBIENT_TEMP_FILENAME + "\" in " + this.getFilesDir());
    }
    sc.close();

    Log.v("readAmbientTempFile", "Ambient temperature file (" + AMBIENT_TEMP_FILENAME + ") read: " + ambientTemp);

    return ambientTemp;
  }

  private void writeAmbientTempFile() throws IOException {
    FileOutputStream outputStream;

    float ambientTemp = mSensorRecorderThread.getAmbientTemperature();
    String ambientTempStr = String.format(Locale.getDefault(), "%.1f", ambientTemp);

    outputStream = openFileOutput(AMBIENT_TEMP_FILENAME, Context.MODE_PRIVATE);

    //Log.v("writeAmbientTempFile", "Writing \"" + ambientTempStr + "\" to " + AMBIENT_TEMP_FILENAME);

    outputStream.write(ambientTempStr.getBytes());
    outputStream.close();
  }

  /**
   *
   * ASYNC TASK: Benchmark Runner
   *
   *  Perform benchmark in the background.
   *
   */
  private class BenchmarkRunner extends AsyncTask<Integer, Float, TimeInterval> {

    private static final String TAG = "BenchmarkRunner";

    private int m_ThreadNumber = 0;

    private static final long IDLE_DELAY_MS = 5 * 1000;

    @Override
    protected TimeInterval doInBackground(Integer... params) {
      // give the thread highest priority possible, so they
      // are scheduled as quickly as possible
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);

      // store the timing of the benchmark
      TimeInterval timing = new TimeInterval(0, 0);

      // thread id
      m_ThreadNumber = params[0];
      Log.v(TAG, "BenchmarkRunner thread " + m_ThreadNumber + " started.");

      //
      // BENCHMARK SETUP
      //------------------------------------------------------------------
      double min_time = Constants.RESOLUTION_DEFAULT * 4.0;
      int FFT_size = Constants.FFT_SIZE;
      int SOR_size =  Constants.SOR_SIZE;
      int LU_size = Constants.LU_SIZE;
      int Sparse_size_M = Constants.SPARSE_SIZE_M;
      int Sparse_size_nz = Constants.SPARSE_SIZE_nz;
      double res = 0.0;
      Random R = new Random(Constants.RANDOM_SEED);


      // start with an idle delay before benchmark
      //------------------------------------------------------------------
      if (TIMED_BENCHMARK) {
        try {
          Thread.sleep(IDLE_DELAY_MS);
        } catch (InterruptedException e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
        }
      }

      // set frequency
      //------------------------------------------------------------------
      if (BENCHMARK_SETS_FREQUENCY) {
        try {
          boolean success = mThermalManagementThread.setCoreFrequency(m_ThreadNumber, Testbed.FREQ_1242MHZ);
        } catch (Throwable e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
        }
      }

      //
      // BENCHMARK RUN
      //------------------------------------------------------------------

      /*
       * Timed Benchmark
       */
      if (TIMED_BENCHMARK) {

        // execute benchmark
        timing.startTime = System.currentTimeMillis();

        switch (BENCHMARK_APP) {
          case SMULT:
            //----------------------------
            res = kernel.measureSparseMatmult(Sparse_size_M, Sparse_size_nz, min_time, R);
            break;

          case LU:
            //----------------------------
            res = kernel.measureLU( LU_size, min_time, R);
            break;

          case SOR:
          default:
            res = kernel.measureSOR( SOR_size, min_time, R);
            //res = kernel.measureFFT( FFT_size, min_time, R); // doesn't work
            break;
        }

        timing.stopTime = System.currentTimeMillis();


      /*
       * Continuous Benchmark
       */
      } else {

        // parameters for all benchmarks
        int cycles = 2048;

        // prepare sor parameters
        double G[][] = kernel.RandomMatrix(SOR_size, SOR_size, R);

        // prepare smult parameters
        double x[] = kernel.RandomVector(Sparse_size_M, R);
        double y[] = new double[Sparse_size_M];
        int nr = Sparse_size_nz / Sparse_size_M;    // average number of nonzeros per row
        int anz = nr * Sparse_size_M;   // _actual_ number of nonzeros
        double val[] = kernel.RandomVector(anz, R);
        int col[] = new int[anz];
        int row[] = new int[Sparse_size_M+1];
        row[0] = 0;
        for (int r=0; r<Sparse_size_M; r++) {
          int rowr = row[r];
          row[r+1] = rowr + nr;
          int step = r/ nr;
          if (step < 1) step = 1;   // take at least unit steps
          for (int i=0; i<nr; i++)
            col[rowr+i] = i*step;
        }

        // prepare lu parameters
        double A[][] = kernel.RandomMatrix(LU_size, LU_size,  R);
        double lu[][] = new double[LU_size][LU_size];
        int pivot[] = new int[LU_size];



        timing.startTime = System.currentTimeMillis();

        switch (BENCHMARK_APP) {
          case SMULT:
            while (true) {
              SparseCompRow.matmult(y, val, row, col, x, cycles); // execute SMULT
              if (isCancelled()) break;
            }
            break;

          case LU:
            while (true) {
              kernel.CopyMatrix(lu, A);
              LU.factor(lu, pivot); // execute LU
              if (isCancelled()) break;
            }
            break;

          case SOR:
          default:
            while (true) {
              SOR.execute(1.25, G, cycles); // execute SOR
              if (isCancelled()) break;
            }
            break;
        }

        timing.stopTime = System.currentTimeMillis();
      }
      //------------------------------------------------------------------

      Log.v(TAG, "BenchmarkRunner thread " + m_ThreadNumber + " ended. Run time: " + String.format("%.2f", timing.getIntervalSeconds()) + " seconds.");
      return timing;
    }

    @Override
    protected void onPostExecute(TimeInterval result) {
      // store benchmark timing
      latestBenchmarkTimes.get(m_ThreadNumber).setTimes(result.startTime, result.stopTime);

      // record benchmark has ended
      asyncBenchmarkRunning[m_ThreadNumber] = false;

      if (ENABLE_GUI_DEBUG) {
        debugLogMessage("BenchmarkRunner: Core " + (m_ThreadNumber+1) + ": Finished benchmark in " +
                String.format("%.3f", (float)(result.stopTime - result.startTime) / 1000.f) + " seconds");
      }

      boolean otherThreadsRunning = false;
      for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
        otherThreadsRunning = otherThreadsRunning || asyncBenchmarkRunning[i];
      }

      // if no other threads running, set the benchmark button off
      if (!otherThreadsRunning) {
        ToggleButton toggleButton_benchmark = (ToggleButton) findViewById(R.id.toggleButton_benchmark);
        toggleButton_benchmark.setChecked(false);

        // turn off thermal management
        mThermalManagementThread.setManagementEnabled(false);
      }
    }

    @Override
    protected void onCancelled(TimeInterval result) {
      if (m_ThreadNumber == 0) {
        // uncheck the record button
        ToggleButton toggleButton_benchmark = (ToggleButton) findViewById(R.id.toggleButton_benchmark);
        toggleButton_benchmark.setChecked(false);
      }

      // store benchmark timing
      latestBenchmarkTimes.get(m_ThreadNumber).setTimes(result.startTime, result.stopTime);

      if (ENABLE_GUI_DEBUG)
        debugLogMessage("BenchmarkRunner: Core " + (m_ThreadNumber+1) + ": Cancelled benchmark!");
    }

  } // private class BenchmarkRunner
}
