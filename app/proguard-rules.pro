# Keep ics-openvpn classes that are accessed via reflection / AIDL
-keep class de.blinkt.openvpn.** { *; }
-keep class de.blinkt.openvpn.core.** { *; }
-keep class de.blinkt.openvpn.api.** { *; }

# Keep VpnProfile (serialized)
-keep class de.blinkt.openvpn.VpnProfile { *; }
-keep class de.blinkt.openvpn.core.Connection { *; }
-keep class de.blinkt.openvpn.core.Connection$* { *; }

# Keep Parcelable / AIDL generated classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
