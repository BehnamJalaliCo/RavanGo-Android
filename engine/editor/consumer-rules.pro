# Editor voice cleanup reads the DSP latency reflectively.
-keepclassmembers class com.ravango.core.media.dsp.VoiceProcessor { public int getLatencySamples(); }
