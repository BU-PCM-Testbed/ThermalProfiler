package com.testbed.peaclab.thermalprofiler;

public abstract class Thermocouple {
  
  // thermocouple reference data:
  // array index represents temperature in Celsius.
  // array element represents thermocouple voltage in Volts.
  // e.g.:
  //
  //   TCPL_LOOKUP_TABLE[8] = 0.000312f
  //     represents 8 deg Celsius when voltage is 3.12e-4 Volts
  //
  private static final float[] TCPL_LOOKUP_TABLE = {
      0.000000f, 0.000039f, 0.000078f, 0.000117f, 0.000156f, 0.000195f, 0.000234f, 0.000273f, 0.000312f, 0.000352f, 0.000391f, 0.000431f, 0.000470f, 0.000510f, 0.000549f, 0.000589f,
      0.000629f, 0.000669f, 0.000709f, 0.000749f, 0.000790f, 0.000830f, 0.000870f, 0.000911f, 0.000951f, 0.000992f, 0.001033f, 0.001074f, 0.001114f, 0.001155f, 0.001196f, 0.001238f,
      0.001279f, 0.001320f, 0.001362f, 0.001403f, 0.001445f, 0.001486f, 0.001528f, 0.001570f, 0.001612f, 0.001654f, 0.001696f, 0.001738f, 0.001780f, 0.001823f, 0.001865f, 0.001908f,
      0.001950f, 0.001993f, 0.002036f, 0.002079f, 0.002122f, 0.002165f, 0.002208f, 0.002251f, 0.002294f, 0.002338f, 0.002381f, 0.002425f, 0.002468f, 0.002512f, 0.002556f, 0.002600f,
      0.002643f, 0.002687f, 0.002732f, 0.002776f, 0.002820f, 0.002864f, 0.002909f, 0.002953f, 0.002998f, 0.003043f, 0.003087f, 0.003132f, 0.003177f, 0.003222f, 0.003267f, 0.003312f,
      0.003358f, 0.003403f, 0.003448f, 0.003494f, 0.003539f, 0.003585f, 0.003631f, 0.003677f, 0.003722f, 0.003768f, 0.003814f, 0.003860f, 0.003907f, 0.003953f, 0.003999f, 0.004046f,
      0.004092f, 0.004138f, 0.004185f, 0.004232f, 0.004279f, 0.004325f, 0.004372f, 0.004419f, 0.004466f, 0.004513f, 0.004561f, 0.004608f, 0.004655f, 0.004702f, 0.004750f, 0.004798f,
      0.004845f, 0.004893f, 0.004941f, 0.004988f, 0.005036f, 0.005084f, 0.005132f, 0.005180f, 0.005228f, 0.005277f, 0.005325f, 0.005373f, 0.005422f, 0.005470f, 0.005519f, 0.005567f,
      0.005616f, 0.005665f, 0.005714f, 0.005763f, 0.005812f, 0.005861f, 0.005910f, 0.005959f, 0.006008f, 0.006057f, 0.006107f, 0.006156f, 0.006206f, 0.006255f, 0.006305f, 0.006355f,
      0.006404f, 0.006454f, 0.006504f, 0.006554f, 0.006604f, 0.006654f, 0.006704f, 0.006754f, 0.006805f, 0.006855f, 0.006905f, 0.006956f, 0.007006f, 0.007057f, 0.007107f, 0.007158f,
      0.007209f, 0.007260f, 0.007310f, 0.007361f, 0.007412f, 0.007463f, 0.007515f, 0.007566f, 0.007617f, 0.007668f, 0.007720f, 0.007771f, 0.007823f, 0.007874f, 0.007926f, 0.007977f,
      0.008029f, 0.008081f, 0.008133f, 0.008185f, 0.008237f, 0.008289f, 0.008341f, 0.008393f, 0.008445f, 0.008497f, 0.008550f, 0.008602f, 0.008654f, 0.008707f, 0.008759f, 0.008812f,
      0.008865f, 0.008917f, 0.008970f, 0.009023f, 0.009076f, 0.009129f, 0.009182f, 0.009235f, 0.009288f, 0.009341f, 0.009395f, 0.009448f, 0.009501f, 0.009555f, 0.009608f, 0.009662f,
      0.009715f, 0.009769f, 0.009822f, 0.009876f, 0.009930f, 0.009984f, 0.010038f, 0.010092f, 0.010146f, 0.010200f, 0.010254f, 0.010308f, 0.010362f, 0.010417f, 0.010471f, 0.010525f,
      0.010580f, 0.010634f, 0.010689f, 0.010743f, 0.010798f, 0.010853f, 0.010907f, 0.010962f, 0.011017f, 0.011072f, 0.011127f, 0.011182f, 0.011237f, 0.011292f, 0.011347f, 0.011403f,
      0.011458f, 0.011513f, 0.011569f, 0.011624f, 0.011680f, 0.011735f, 0.011791f, 0.011846f, 0.011902f, 0.011958f, 0.012013f, 0.012069f, 0.012125f, 0.012181f, 0.012237f, 0.012293f,
      0.012349f, 0.012405f, 0.012461f, 0.012518f, 0.012574f, 0.012630f, 0.012687f, 0.012743f, 0.012799f, 0.012856f, 0.012912f, 0.012969f, 0.013026f, 0.013082f, 0.013139f, 0.013196f,
      0.013253f, 0.013310f, 0.013366f, 0.013423f, 0.013480f, 0.013537f, 0.013595f, 0.013652f, 0.013709f, 0.013766f, 0.013823f, 0.013881f, 0.013938f, 0.013995f, 0.014053f, 0.014110f,
      0.014168f, 0.014226f, 0.014283f, 0.014341f, 0.014399f, 0.014456f, 0.014514f, 0.014572f, 0.014630f, 0.014688f, 0.014746f, 0.014804f, 0.014862f, 0.014920f, 0.014978f, 0.015036f,
      0.015095f, 0.015153f, 0.015211f, 0.015270f, 0.015328f, 0.015386f, 0.015445f, 0.015503f, 0.015562f, 0.015621f, 0.015679f, 0.015738f, 0.015797f, 0.015856f, 0.015914f, 0.015973f,
      0.016032f, 0.016091f, 0.016150f, 0.016209f, 0.016268f, 0.016327f, 0.016387f, 0.016446f, 0.016505f, 0.016564f, 0.016624f, 0.016683f, 0.016742f, 0.016802f, 0.016861f, 0.016921f,
      0.016980f, 0.017040f, 0.017100f, 0.017159f, 0.017219f, 0.017279f, 0.017339f, 0.017399f, 0.017458f, 0.017518f, 0.017578f, 0.017638f, 0.017698f, 0.017759f, 0.017819f, 0.017879f,
      0.017939f, 0.017999f, 0.018060f, 0.018120f, 0.018180f, 0.018241f, 0.018301f, 0.018362f, 0.018422f, 0.018483f, 0.018543f, 0.018604f, 0.018665f, 0.018725f, 0.018786f, 0.018847f,
      0.018908f, 0.018969f, 0.019030f, 0.019091f, 0.019152f, 0.019213f, 0.019274f, 0.019335f, 0.019396f, 0.019457f, 0.019518f, 0.019579f, 0.019641f, 0.019702f, 0.019763f, 0.019825f,
      0.019886f, 0.019947f, 0.020009f, 0.020070f, 0.020132f, 0.020193f, 0.020255f, 0.020317f, 0.020378f, 0.020440f, 0.020502f, 0.020563f, 0.020625f, 0.020687f, 0.020748f, 0.020810f
    };
  
  private static final float TCPL_VOLTAGE_MIN = TCPL_LOOKUP_TABLE[0];
  private static final float TCPL_VOLTAGE_MAX = TCPL_LOOKUP_TABLE[TCPL_LOOKUP_TABLE.length - 1];
  
  private static final float TCPL_TEMPERATURE_MIN = 0.f;
  private static final float TCPL_TEMPERATURE_MAX = (float)(TCPL_LOOKUP_TABLE.length - 1);
  
  public static float voltsToCelsius(float volts) throws IllegalArgumentException, ArrayIndexOutOfBoundsException {
    float celsius = 0.f;
    
    // check arguments
    if (volts < TCPL_VOLTAGE_MIN) {
      throw new IllegalArgumentException("voltage below minimum");
    }
    
    if (volts > TCPL_VOLTAGE_MAX) {
      throw new IllegalArgumentException("voltage above maximum");
    }
    
    // init
    int upperIndex = -1;
    int lowerIndex = -1;
    
    // search for voltage that is just above the given voltage
    for (int i = 0; i < TCPL_LOOKUP_TABLE.length; i++) {
      if (TCPL_LOOKUP_TABLE[i] >= volts) {
        upperIndex = i;
        lowerIndex = upperIndex - 1;
        break;
      }
    }
    
    // special case: given voltage is first element in array
    if (upperIndex == 0) {
      return TCPL_LOOKUP_TABLE[upperIndex];
    }
    
    // detect error condition
    else if (upperIndex < 0) {
      throw new ArrayIndexOutOfBoundsException("voltage index returned " + upperIndex);
    }
    
    // linear interpolation of temperature
    float fraction = (volts - TCPL_LOOKUP_TABLE[lowerIndex]) / (TCPL_LOOKUP_TABLE[upperIndex] - TCPL_LOOKUP_TABLE[lowerIndex]);
    celsius = ((float)lowerIndex + fraction);
    
    return celsius;
  }
  
  public static float celsiusToVolts(float celsius) throws IllegalArgumentException {
    float volts = 0.f;
    
    // check arguments
    if (celsius < TCPL_TEMPERATURE_MIN) {
      throw new IllegalArgumentException("temperature below minimum");
    }
    
    if (celsius > TCPL_TEMPERATURE_MAX) {
      throw new IllegalArgumentException("temperature above maximum");
    }
    
    // init
    float fraction = celsius - (float)(Math.floor(celsius));
    int lowerIndex = (int) celsius;
    int upperIndex = (int) Math.ceil(celsius);
    
    // linear interpolation of voltage
    volts = TCPL_LOOKUP_TABLE[lowerIndex] + ((TCPL_LOOKUP_TABLE[upperIndex] - TCPL_LOOKUP_TABLE[lowerIndex]) * fraction);
    
    return volts;
  }
}
