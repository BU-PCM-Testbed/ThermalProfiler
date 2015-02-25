package com.testbed.peaclab.thermalprofiler;

public class TestbedTemperatures {
  public long timestamp = 0;
  public short temperatureCore0 = 0;
  public short temperatureCore1 = 0;
  public short temperatureCore2 = 0;
  public short temperatureCore3 = 0;
  public float temperatureThermocouple = 0.f;
  public float temperatureAmbient = 0.f;
  public float energyPCM = 0.f;
  public float R_si = 0.f;
  public float R_pcm = 0.f;
  
  
  public TestbedTemperatures() {
    timestamp = 0;
    temperatureCore0 = 0;
    temperatureCore1 = 0;
    temperatureCore2 = 0;
    temperatureCore3 = 0;
    temperatureThermocouple = 0.f;
    temperatureAmbient = 0.f;
    energyPCM = 0.f;
    R_si = 0.f;
    R_pcm = 0.f;
  }
  
  public TestbedTemperatures(
      long timestamp, 
      short temperatureCore0, short temperatureCore1, 
      short temperatureCore2, short temperatureCore3,
      float temperatureThermocouple, 
      float temperatureAmbient,
      float energyPCM,
      float R_si, float R_pcm) {
    
    this.timestamp = timestamp;
    this.temperatureCore0 = temperatureCore0;
    this.temperatureCore1 = temperatureCore1;
    this.temperatureCore2 = temperatureCore2;
    this.temperatureCore3 = temperatureCore3;
    this.temperatureThermocouple = temperatureThermocouple;
    this.temperatureAmbient = temperatureAmbient;
    this.energyPCM = energyPCM;
    this.R_si = R_si;
    this.R_pcm = R_pcm;
  }
  
  public TestbedTemperatures(TestbedTemperatures copy) {
    this.copy(copy);
  }
  
  public void copy(TestbedTemperatures copy) {
    this.timestamp = copy.timestamp;
    this.temperatureCore0 = copy.temperatureCore0;
    this.temperatureCore1 = copy.temperatureCore1;
    this.temperatureCore2 = copy.temperatureCore2;
    this.temperatureCore3 = copy.temperatureCore3;
    this.temperatureThermocouple = copy.temperatureThermocouple;
    this.temperatureAmbient = copy.temperatureAmbient;
    this.energyPCM = copy.energyPCM;
    this.R_si = copy.R_si;
    this.R_pcm = copy.R_pcm;
  }
}
