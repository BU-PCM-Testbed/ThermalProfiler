package com.testbed.peaclab.thermalprofiler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Environment;
import android.util.Log;

public class SensorRecorder extends Thread {
  
  private static final String TAG = "SensorRecorder";
  
  // set the recording interval, in milliseconds
  private static final int SAMPLING_INTERVAL_MS = 500;
  
  // set the default number of data elements to store
  private static final int DEFAULT_SAMPLE_STORAGE = 600;
  
  // map a CPU core to a thermal sensor file handle
  private static final String[] CPU_CORE_THERMAL_SENSOR_FILENAMES = {
      "/sys/class/thermal/thermal_zone7/temp",
      "/sys/class/thermal/thermal_zone8/temp",
      "/sys/class/thermal/thermal_zone9/temp",
      "/sys/class/thermal/thermal_zone10/temp"
    };
  
  private static final String SENSOR_DATA_LOG_FILENAME = "stat.csv";
  private static final String SENSOR_EVENT_LOG_FILENAME = "event.csv";
  

  public static final float PCM_MELTING_TEMP = 55.f;
  public static final float PCM_FREEZING_TEMP = 60.f;
  public static final float PCM_ENERGY_MAX = 230.f;
  
  
  // Use a RandomAccessFile to read the temperature files on the
  // filesystem. RandomAccessFile allows "rewinding" to re-read
  // from the beginning of the file, without having to close and
  // re-open the file. Index by core:
  //   0 = core 0  (/sys/class/thermal/thermal_zone7/temp)
  //   1 = core 1  (/sys/class/thermal/thermal_zone8/temp)
  //   2 = core 2  (/sys/class/thermal/thermal_zone9/temp)
  //   3 = core 3  (/sys/class/thermal/thermal_zone10/temp)
  //
  ArrayList<RandomAccessFile> mCpuCoreTempFiles;
  
  // this USB device corresponds to the Agilent U1252A multimeter, which
  // uses a Prolific serial port interface.
  private UsbManager mUsbManager;
  private UsbDevice mAgilentDevice;
  private UsbSerialPort mAgilentPort;
  private AgilentSampler mAgilentSamplerThread;
  
  // keep track of how many samples have been recorded
  private int mSampleCounter;
  private float mSampleTime;
  
  // recording state
  private boolean mRecordSensors;
  private boolean mRecordSensors_prev;
  
  // temperature records
  private TestbedTemperatures mCurrentTestbedTemperatures;
  private ArrayList<TestbedTemperatures> mTestbedTemperatures;
  
  // ambient temperature
  private float mAmbientTemperature;
  
  // pcm energy counters
  private float mPCMEnergy;
  private float mPCMEnergy_Saturated; // this counter will be capped at PCM_ENERGY_MAX
  private boolean mPCMMelted;
  
  // benchmark timestamps
  private TimeInterval mBenchmarkTime;
  
  // termination condition
  private volatile boolean mTerminate;
  
  
  public SensorRecorder(UsbManager usbManager, float ambientTemp) {
    mUsbManager = usbManager;
    
    mCpuCoreTempFiles = new ArrayList<RandomAccessFile>(Testbed.TESTBED_NUM_CPU_CORES);
    
    mAgilentDevice = null;
    
    mSampleCounter = 0;
    mSampleTime = 0.f;
    
    mRecordSensors = false;
    mRecordSensors_prev = false;
    
    mCurrentTestbedTemperatures = new TestbedTemperatures();
    mTestbedTemperatures = new ArrayList<TestbedTemperatures>(DEFAULT_SAMPLE_STORAGE);
    
    mAmbientTemperature = ambientTemp;
    mPCMEnergy = 0.f;
    mPCMEnergy_Saturated = 0.f;
    mPCMMelted = false;
    
    mBenchmarkTime = new TimeInterval(0,0);
    
    mTerminate = false;
  }
  
  
  @Override
  public void run() {
    long T_start = 0;
    long T_stop = 0;
    long T_sleep = 0;
    long T_prev = System.currentTimeMillis();
    
    Log.v(TAG, "Starting SensorRecorder thread");
    
    // attempt to create file handles for CPU core temperatures
    try {
      openCoreTemperatureFiles();
    } catch (Throwable e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
      mTerminate = true;
    }
    
    try {
      openAgilentPort();
      sleep(300);
    } catch (Throwable e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }

    while (!mTerminate) {
      // start loop
      T_start = System.currentTimeMillis();
      mSampleTime = (T_start - T_prev) / 1000.f;
      
      // get recording state
      boolean recordState = getRecordState();
      
      // detect if we are starting to record
      if (!mRecordSensors_prev && recordState) {
        // clear out temperature records
        mTestbedTemperatures.clear();
        Log.i(TAG, "Data recording activated");
      }
      // detect if we are ending recording
      else if (mRecordSensors_prev && !recordState) {
        // dump data to file
        Log.i(TAG, "Data recording terminated");
        logSensorRecords();
      }
      
      // sample testbed sensors
      mCurrentTestbedTemperatures = sampleSensors();
      
      // save data if we are recording
      if (recordState) {
        mTestbedTemperatures.add(mCurrentTestbedTemperatures);
      }
      
      // wrap up
      mRecordSensors_prev = recordState;
      T_stop = System.currentTimeMillis();
      T_prev = T_start;

      //Log.d(TAG, "SensorRecorder loop: " + (T_stop - T_start) + " msec");
      
      // sleep until next sampling period
      T_sleep = SAMPLING_INTERVAL_MS - (T_stop - T_start);
      T_sleep = (T_sleep < 0) ? 0 : T_sleep;
      try {
        Thread.sleep(T_sleep);
      } catch (InterruptedException e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }
    }
    
    // attempt to close sensors
    try {
      closeCoreTemperatureFiles();
      mAgilentSamplerThread.terminate();
    } catch (IOException e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
    }
    
    Log.v(TAG, "Terminated SensorRecorder thread");
  }
  
  private TestbedTemperatures sampleSensors() {
    TestbedTemperatures dataSample = new TestbedTemperatures();
    float tcplVoltage = 0.f;

    long T0 = 0, T1 = 0;

    // sample thermocouple sensor
    try {
      tcplVoltage = readAgilentPort();
      //tcplVoltage = 0.0013f;
    } catch (IOException e) {
      Log.w("sampleSensors", e.getMessage());
      Log.i("sampleSensors", "Attempting to re-open Agilent port ...");

      if (mAgilentSamplerThread != null) {
        mAgilentSamplerThread.terminate();
      }
      
      // attempt to re-open serial port
      try {
        openAgilentPort();
      } catch (IOException e1) {
        Log.w("sampleSensors", e1.getMessage());
      }
      
    }
    
    long timestamp = System.currentTimeMillis();
    
    // sample CPU temperature sensors
    //T0 = System.currentTimeMillis();
    dataSample.timestamp = timestamp;
    dataSample.temperatureCore0 = readCoreTemperature(0);
    dataSample.temperatureCore1 = readCoreTemperature(1);
    dataSample.temperatureCore2 = readCoreTemperature(2);
    dataSample.temperatureCore3 = readCoreTemperature(3);
    dataSample.temperatureAmbient = mAmbientTemperature;
    //T1 = System.currentTimeMillis();

    try {
      dataSample.temperatureThermocouple = Thermocouple.voltsToCelsius(tcplVoltage) + mAmbientTemperature;
      //dataSample.temperatureThermocouple = tcplVoltage;
    } catch (Throwable e) {
      Log.w("sampleSensors", "TCPL Voltage = " + String.format("%.6f", tcplVoltage));
      Log.e(e.getClass().toString(), e.getMessage(), e);
      dataSample.temperatureThermocouple = 0.f;
    }

    // calculate average core temperature
    float cpuTemperature = (float)(
        dataSample.temperatureCore0 + 
        dataSample.temperatureCore1 + 
        dataSample.temperatureCore2 + 
        dataSample.temperatureCore3) / ((float)Testbed.TESTBED_NUM_CPU_CORES);
    float pcmTemperature = dataSample.temperatureThermocouple;
    float airTemperature = dataSample.temperatureAmbient;

    // PCM energy calculations
    float dT_si  = Math.max(Math.abs(cpuTemperature - pcmTemperature), 0.3f);
    float dT_pcm = Math.max(Math.abs(pcmTemperature - airTemperature), 0.3f);
    float R_si   = (float) (0.35 * Math.log(dT_si) + 0.54);
    float R_pcm  = (float) (0.0436 * Math.log(dT_pcm) + 12.221);
    dataSample.R_si = R_si;
    dataSample.R_pcm = R_pcm;
    
    float pwrIn     = (cpuTemperature - pcmTemperature) / R_si;
    float pwrOut    = (pcmTemperature - airTemperature) / R_pcm;
    float netPower  = pwrIn - pwrOut;
    
    // report stored PCM energy
    if (pcmTemperature < PCM_MELTING_TEMP) {
      mPCMEnergy = 0.f;
    } else {
      mPCMEnergy += netPower * mSampleTime;
    }
    dataSample.energyPCM = mPCMEnergy;
    
    
    // keep a 2nd pcm counter that caps at PCM_ENERGY_MAX
    // and incorporates freezing
    if (!mPCMMelted && (pcmTemperature < PCM_MELTING_TEMP)) {
      mPCMEnergy_Saturated = 0.f;
    } else if (!mPCMMelted) {
      mPCMEnergy_Saturated += netPower * mSampleTime;
      if (mPCMEnergy_Saturated >= PCM_ENERGY_MAX) {
        mPCMEnergy_Saturated = PCM_ENERGY_MAX;
        mPCMMelted = true;
      }
    } else if (mPCMMelted && (pcmTemperature < PCM_FREEZING_TEMP)) {
      mPCMEnergy_Saturated += netPower * mSampleTime;
      
      if (mPCMEnergy_Saturated < 0.f) {
        mPCMEnergy_Saturated = 0.f;
        mPCMMelted = false;
      }
    }


    Log.v(TAG,
        "T_CPU=" + String.format("%.2f", cpuTemperature) + ", " +
        //"T_CPU=" + dataSample.temperatureCore0 + ", " +
        "T_PCM=" + String.format("%.2f", pcmTemperature) + ", " +
        "E_PCM=" + String.format("%6.2f", mPCMEnergy)    + " (" + String.format("%.2f", mPCMEnergy_Saturated) + "), " +
        "P_net=" + String.format("%6.3f", netPower)      + ", " +
        "Rsi/pcm= " + String.format("%.3f", R_si) + ", " + String.format("%.3f", R_pcm)
    );

    
    return dataSample;
  }
  
  private short readCoreTemperature(int core) throws IllegalArgumentException {
    short temperature = 0;
    
    // check arguments
    if (core < Testbed.TESTBED_CPU_CORE_INDEX_MIN || core > Testbed.TESTBED_CPU_CORE_INDEX_MAX) {
      throw new IllegalArgumentException("invalid core index " + core);
    }
    
    RandomAccessFile coreTemperatureFile = mCpuCoreTempFiles.get(core);
    String fileContents = "";
    
    try {
      String line;
      while ((line = coreTemperatureFile.readLine()) != null) {
        fileContents = fileContents.concat(line);
      }
      //Log.v("readCoreTemp", "Core " + coreNum + " Temp: " + fileContents);
      
      // parse temperature
      temperature = Short.parseShort(fileContents);
      
      // reset the file
      coreTemperatureFile.seek(0);
    } catch (IOException e) {
      //Log.e(e.getClass().toString(), e.getMessage(), e);
      Log.e("readCoreTemperature", "Unable to read core temperature, check file permissions!");
      temperature = 0;
    }
    
    return temperature;
  }
  
  private void logSensorRecords() {
    
    FileOutputStream fos = null;
    boolean error = false;
    
    /**
     * Identify what data we are dumping. The first character
     * of the data file denotes the format of the rest of the
     * file.
     * 
     *   T      Recording only the cores' temperatures
     *   U      Recording cores' temperatures and utilization
     *   
     *   t      Same as T, but additional field appended at the end, representing Unix timestamp
     *   u      Same as U, but additional field appended at the end, representing Unix timestamp
     *   
     *   p      Record CPU core temperatures, thermocouple
     *   e      Record p + E_pcm,R_si,R_pcm
     *   
     */
    //char recordCode = 'p';
    char recordCode = 'e';
    
    // define how to store timestamps
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    
    // format the benchmark start time
    long startTime = getBenchmarkTime().startTime;
    String startTimeStr = (startTime != 0) ? sdf.format(new Date(startTime)) : "0";
    
    // format the benchmark stop time
    long stopTime = getBenchmarkTime().stopTime;
    String stopTimeStr = (stopTime != 0) ? sdf.format(new Date(stopTime)) : "0";
    
    // check if we can store on the SD card
    String state = Environment.getExternalStorageState();
    if (!state.equals(Environment.MEDIA_MOUNTED)) {
      Log.w(TAG, "Unable to store file! SD Card State: " + state);
      return;
    }
    
    // give it a file name
    File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SENSOR_DATA_LOG_FILENAME);
    
    // attempt to open file
    try {
      fos = new FileOutputStream(file);
      String line, date;
      TestbedTemperatures data;
      
      // record benchmark times
      line = recordCode + "," + startTimeStr + "," + stopTimeStr + "\n";
      fos.write(line.getBytes());
      
      // record data points
      for (int i = 0; i < mTestbedTemperatures.size(); i++) {
        data = mTestbedTemperatures.get(i);
        date = sdf.format(data.timestamp);
        line = date + "," +
            data.temperatureCore0 + "," +
            data.temperatureCore1 + "," +
            data.temperatureCore2 + "," +
            data.temperatureCore3 + "," +
            String.format("%.3f", data.temperatureThermocouple) + "," + 
            String.format("%.1f", data.temperatureAmbient) + "," +
            String.format("%.4f", data.energyPCM) + "," + 
            String.format("%.4f", data.R_si) + "," +
            String.format("%.4f", data.R_pcm) + /*"," + 
            data.timestamp +*/
            "\n";
        fos.write(line.getBytes());
      }
    }
    // catch any errors
    catch (Throwable e) {
      Log.e(e.getClass().toString(), e.getMessage(), e);
      error = true;
    }
    // close out the file at the end
    finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
          error = true;
        }
      }
    }
    
    if (!error) {
      Log.i(TAG, "Data saved to: " + file.getAbsolutePath());
    }
    
  }
  
  private void openCoreTemperatureFiles() throws FileNotFoundException {
    mCpuCoreTempFiles.clear();
    
    for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
      RandomAccessFile coreTemperatureFile = new RandomAccessFile(CPU_CORE_THERMAL_SENSOR_FILENAMES[i], "r");
      mCpuCoreTempFiles.add(coreTemperatureFile);
    }
  }
  
  private void closeCoreTemperatureFiles() throws IOException {
    for (int i = 0; i < Testbed.TESTBED_NUM_CPU_CORES; i++) {
      RandomAccessFile coreTemperatureFile = mCpuCoreTempFiles.get(i);
      coreTemperatureFile.close();
    }
  }
  
  private void openAgilentPort() throws IOException {
    UsbDevice agilentDevice = getAgilentDevice();
    if (agilentDevice == null) {
      throw new IOException("Agilent device not ready!");
    }
    
    UsbSerialDriver serialDriver = new ProlificSerialDriver(mAgilentDevice);
    UsbDeviceConnection connection = mUsbManager.openDevice(mAgilentDevice);
    
    List<UsbSerialPort> serialPorts = serialDriver.getPorts();
    
    if (serialPorts.size() > 0) {
      // get serial port
      mAgilentPort = serialPorts.get(0);
      
      mAgilentPort.open(connection);
      mAgilentPort.setParameters(19200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
      mAgilentPort.purgeHwBuffers(true, true);
    }

    mAgilentSamplerThread = new AgilentSampler(mAgilentPort);
    mAgilentSamplerThread.start();
  }
  
  private float readAgilentPort() throws IOException {
    float voltage = 0.f;
    if (mAgilentSamplerThread == null) {
      throw new IOException("AgilentSampler not ready!");
    }

    voltage = mAgilentSamplerThread.getSample();
    return voltage;
  }

  public synchronized void terminate() {
    mTerminate = true;
  }
  
  public synchronized void setRecordState(boolean active) {
    mRecordSensors = active;
  }
  
  public synchronized boolean getRecordState() {
    return mRecordSensors;
  }
  
  public synchronized void setAmbientTemperature(float temperature) {
    mAmbientTemperature = temperature;
  }
  
  public synchronized float getAmbientTemperature() {
    return mAmbientTemperature;
  }
  
  public synchronized void setBenchmarkTime(TimeInterval time) {
    mBenchmarkTime.setTimes(time);
  }
  
  public synchronized TimeInterval getBenchmarkTime() {
    return mBenchmarkTime;
  }
  
  public synchronized void setAgilentDevice(UsbDevice device) {
    mAgilentDevice = device;

    if (mAgilentDevice == null && mAgilentSamplerThread != null) {
      mAgilentSamplerThread.terminate();
      mAgilentSamplerThread = null;
    }
  }
  
  public synchronized UsbDevice getAgilentDevice() {
    return mAgilentDevice;
  }
  
  public synchronized TestbedTemperatures getCurrentTestbedTemperatures() {
    return mCurrentTestbedTemperatures;
  }

  private class AgilentSampler extends Thread {
    private final String TAG = "AgilentSampler";

    // set the sampling interval, in milliseconds.
    // note, the serial communication with the Agilent multimeter
    // is slow. reading a full voltage value takes about 300-400 msec.
    // I suspect this may be due to a sloppy serial port interface
    // (the hoho.android.usbserial package).
    private static final int SAMPLING_INTERVAL_MS = 1000;

    private static final int AGILENT_WORD_BUFFER_LENGTH = 40; // in bytes
    private static final int AGILENT_READ_BUFFER_LENGTH = 8; // in bytes
    private static final byte AGILENT_START_BYTE = 0x0A;
    private static final byte AGILENT_STOP_BYTE = 0x0D;

    private UsbSerialPort mAgilentPort;

    private boolean mTerminate;

    private volatile float mSample;

    public AgilentSampler(UsbSerialPort agilentPort) {
      this.mAgilentPort = agilentPort;
      mTerminate = false;
      mSample = 0.f;
    }

    public void run() {
      long T_start = 0;
      long T_stop = 0;
      long T_sleep = 0;
      long T_prev = System.currentTimeMillis();

      Log.v(TAG, "Starting AgilentSampler thread");

      while (!mTerminate) {
        // start loop
        T_start = System.currentTimeMillis();
        mSampleTime = (T_start - T_prev) / 1000.f;

        // check port
        if (mAgilentPort == null) {
          Log.w(TAG, "Agilent port not ready!");

        } else {
          int numReads = 0;
          int wordBufferIndex = 0;
          float voltage = 0.f;

          byte[] wordBuffer = new byte[AGILENT_WORD_BUFFER_LENGTH];
          Arrays.fill(wordBuffer, (byte)0);

          while (wordBufferIndex < AGILENT_WORD_BUFFER_LENGTH) {
            byte[] serialReadBuffer = new byte[AGILENT_READ_BUFFER_LENGTH];

            // read incoming data
            int bytesRead = 0;
            try {
              bytesRead = mAgilentPort.read(serialReadBuffer, 100);
            } catch (IOException e) {
              Log.e(e.getClass().toString(), e.getMessage(), e);
            }
            numReads++;

            // continue processing if you've read something
            if (bytesRead > 0) {

              // append the bytes read to the word buffer
              int freeBytes = AGILENT_WORD_BUFFER_LENGTH - wordBufferIndex;
              bytesRead = (bytesRead > freeBytes) ? freeBytes : bytesRead;
              System.arraycopy(serialReadBuffer, 0, wordBuffer, wordBufferIndex, bytesRead);
              wordBufferIndex += bytesRead;

              // when buffer full, parse the word buffer
              if (wordBufferIndex >= AGILENT_WORD_BUFFER_LENGTH) {
                byte[] dataSampleBuffer = parseAgilentBuffer(wordBuffer);

                try {
                  mAgilentPort.purgeHwBuffers(true, true);
                } catch (IOException e) {
                  Log.e(e.getClass().toString(), e.getMessage(), e);
                }

                if (dataSampleBuffer != null) {
                  String dataSampleString = new String(dataSampleBuffer);

                  // parse string into float
                  try {
                    voltage = Float.parseFloat(dataSampleString);
                    setSample(voltage);
                  } catch (NumberFormatException e) {
                    Log.w(TAG, "Unable to parse Agilent buffer!");
                  }

                }
              }
            }
          } // while (!terminate)
        }

        // wrap up
        T_stop = System.currentTimeMillis();
        T_prev = T_start;

        //Log.d(TAG, "AgilentSampler loop: " + (T_stop - T_start) + " msec");

        // sleep until next sampling period
        T_sleep = SAMPLING_INTERVAL_MS - (T_stop - T_start);
        T_sleep = (T_sleep < 0) ? 0 : T_sleep;
        try {
          Thread.sleep(T_sleep);
        } catch (InterruptedException e) {
          Log.e(e.getClass().toString(), e.getMessage(), e);
        }
      }

      try {
        mAgilentPort.close();
      } catch (IOException e) {
        Log.e(e.getClass().toString(), e.getMessage(), e);
      }

      Log.v(TAG, "Terminated AgilentSampler thread");
    }

    private byte[] parseAgilentBuffer(byte[] wordBuffer) {
      int startByteIndex = -1;
      int stopByteIndex = -1;
      byte[] parsedBuffer = null;

      for (int i = 0; i < AGILENT_WORD_BUFFER_LENGTH; i++) {
        if (wordBuffer[i] == AGILENT_START_BYTE) {
          startByteIndex = i;
        }

        if (startByteIndex > 0 && wordBuffer[i] == AGILENT_STOP_BYTE) {
          stopByteIndex = i;
          break;
        }
      }

      if (startByteIndex > 0 && stopByteIndex > startByteIndex) {
        int wordSize = stopByteIndex - startByteIndex - 1;

        if (wordSize > 0) {
          parsedBuffer = Arrays.copyOfRange(wordBuffer, startByteIndex+1, stopByteIndex);
        }
      }

      return parsedBuffer;
    }

    public synchronized void terminate() {
      mTerminate = true;
    }

    public synchronized float getSample() {
      return mSample;
    }

    private synchronized void  setSample(float sample) {
      mSample = sample;
    }

    public void setAgilentPort(UsbSerialPort agilentPort) {
      this.mAgilentPort = agilentPort;
    }
  }
}
