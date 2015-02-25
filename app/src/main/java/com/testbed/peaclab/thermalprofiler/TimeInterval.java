package com.testbed.peaclab.thermalprofiler;

/**
 * 
 * TYPE: TimeInterval
 * 
 *  Private type containing start/stop time pair
 * 
 */
public class TimeInterval {
  public long startTime = 0;
  public long stopTime = 0;
  
  public TimeInterval() {
    this.setTimes(0,0);
  }
  
  public TimeInterval(long unix_time_start, long unix_time_stop) {
    this.setTimes(unix_time_start, unix_time_stop);
  }
  
  public void setTimes(long unix_time_start, long unix_time_stop) {
    startTime = unix_time_start;
    stopTime = unix_time_stop;
  }
  
  public void setTimes(TimeInterval time) {
    this.setTimes(time.startTime, time.stopTime);
  }
  
  public long getIntervalMillis() {
    return (stopTime - startTime);
  }
  
  public float getIntervalSeconds() {
    return (stopTime - startTime) / 1000.f;
  }
} // public class TimeInterval