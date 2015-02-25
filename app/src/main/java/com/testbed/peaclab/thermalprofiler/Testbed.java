package com.testbed.peaclab.thermalprofiler;

import android.util.Log;

public abstract class Testbed {
  public static final int TESTBED_NUM_CPU_CORES = 4;
  
  public static final int TESTBED_NUM_CPU_CORES_MIN = 1;
  public static final int TESTBED_NUM_CPU_CORES_MAX = TESTBED_NUM_CPU_CORES;
  
  public static final int TESTBED_CPU_CORE_INDEX_MIN = 0;
  public static final int TESTBED_CPU_CORE_INDEX_MAX = 3;
  
  // constants for allowable frequencies
  public static final int[] TESTBED_CPU_FREQUENCY = {
    384000,   // 0
    486000,   // 1
    594000,   // 2
    702000,   // 3
    810000,   // 4
    918000,   // 5
    1026000,  // 6
    1134000,  // 7
    1242000   // 8
  };
  public static final int TESTBED_CPU_FREQ_INDEX_MIN = 0;
  public static final int TESTBED_CPU_FREQ_INDEX_MAX = 8;
  
  public static final int FREQ_384MHZ = 0;
  public static final int FREQ_486MHZ = 1;
  public static final int FREQ_594MHZ = 2;
  public static final int FREQ_702MHZ = 3;
  public static final int FREQ_810MHZ = 4;
  public static final int FREQ_918MHZ = 5;
  public static final int FREQ_1026MHZ = 6;
  public static final int FREQ_1134MHZ = 7;
  public static final int FREQ_1242MHZ = 8;
  
  public static int freq2index(int freqHz) {
    int index = 0;
    switch (freqHz) {
    case 384000:
      index = FREQ_384MHZ;
      break;
    case 486000:
      index = FREQ_486MHZ;
      break;
    case 594000:
      index = FREQ_594MHZ;
      break;
    case 702000:
      index = FREQ_702MHZ;
      break;
    case 810000:
      index = FREQ_810MHZ;
      break;
    case 918000:
      index = FREQ_918MHZ;
      break;
    case 1026000:
      index = FREQ_1026MHZ;
      break;
    case 1134000:
      index = FREQ_1134MHZ;
      break;
    case 1242000:
      index = FREQ_1242MHZ;
      break;
    default:
      index = FREQ_384MHZ;
      Log.w("freq2index", "bad argument: " + freqHz);
      break;
    }
    return index;
  }
  
}
