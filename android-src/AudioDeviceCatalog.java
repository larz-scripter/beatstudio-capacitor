package com.larzos.beatstudio;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

/**
 * Turns Android's {@link AudioManager#getDevices(int)} into a plain JSON list the
 * BeatStudio web layer can render in its input/output pickers.
 *
 * Chrome / the System WebView collapse audio inputs into a handful of pseudo
 * devices ("Default / Speakerphone / Wired headset / Headset earpiece") and often
 * never surface a USB mic at all. The native AudioManager sees every real device
 * - USB interfaces included - with a stable (per-connection) id we can hand to
 * {@link android.media.AudioRecord#setPreferredDevice}.
 */
final class AudioDeviceCatalog {

    private AudioDeviceCatalog() {}

    static JSArray list(AudioManager am, int flags) {
        JSArray arr = new JSArray();
        AudioDeviceInfo[] devices = am.getDevices(flags);
        for (AudioDeviceInfo d : devices) {
            JSObject o = new JSObject();
            o.put("id", d.getId());
            o.put("typeCode", d.getType());
            o.put("type", typeSlug(d.getType()));
            o.put("label", labelFor(d));
            o.put("isInput", d.isSource());
            o.put("isOutput", d.isSink());
            o.put("isUsb", isUsb(d.getType()));
            o.put("isBluetooth", isBluetooth(d.getType()));
            o.put("channelCounts", intArray(d.getChannelCounts()));
            o.put("sampleRates", intArray(d.getSampleRates()));
            if (Build.VERSION.SDK_INT >= 28 && d.getProductName() != null) {
                o.put("product", d.getProductName().toString().trim());
            }
            if (Build.VERSION.SDK_INT >= 30 && d.getAddress() != null) {
                o.put("address", d.getAddress());
            }
            arr.put(o);
        }
        return arr;
    }

    /** The single input Android would pick with no preferred device set, if any. */
    static AudioDeviceInfo findById(AudioManager am, int flags, int id) {
        for (AudioDeviceInfo d : am.getDevices(flags)) {
            if (d.getId() == id) return d;
        }
        return null;
    }

    static boolean isUsb(int t) {
        return t == AudioDeviceInfo.TYPE_USB_DEVICE
                || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static boolean isBluetooth(int t) {
        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
        if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
        if (Build.VERSION.SDK_INT >= 33 && t == AudioDeviceInfo.TYPE_BLE_BROADCAST) return true;
        return false;
    }

    static String labelFor(AudioDeviceInfo d) {
        String product = "";
        if (Build.VERSION.SDK_INT >= 28 && d.getProductName() != null) {
            product = d.getProductName().toString().trim();
        }
        String friendly = friendlyType(d.getType());
        // getProductName() returns the phone's own model string for built-in
        // devices - useless as a label, so only trust it for external gear.
        boolean externalName = !product.isEmpty()
                && !product.equalsIgnoreCase(Build.MODEL)
                && !product.equalsIgnoreCase(Build.DEVICE)
                && !product.equalsIgnoreCase(Build.PRODUCT);
        if (externalName && (isUsb(d.getType()) || isBluetooth(d.getType()) || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) {
            return product + " - " + friendly;
        }
        return friendly;
    }

    static String friendlyType(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:        return "Phone mic";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:    return "Phone speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:   return "Earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:      return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:   return "Wired headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE:         return "USB audio device";
            case AudioDeviceInfo.TYPE_USB_HEADSET:        return "USB headset";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:      return "USB accessory";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:     return "Bluetooth (A2DP)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:      return "Bluetooth (headset/SCO)";
            case AudioDeviceInfo.TYPE_HDMI:               return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC:           return "HDMI ARC";
            case AudioDeviceInfo.TYPE_DOCK:               return "Dock";
            case AudioDeviceInfo.TYPE_TELEPHONY:          return "Telephony";
            case AudioDeviceInfo.TYPE_FM:                 return "FM";
            case AudioDeviceInfo.TYPE_FM_TUNER:           return "FM tuner";
            case AudioDeviceInfo.TYPE_TV_TUNER:           return "TV tuner";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:        return "Line in (analog)";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:       return "Line in (digital)";
            case AudioDeviceInfo.TYPE_AUX_LINE:           return "Aux line";
            case AudioDeviceInfo.TYPE_IP:                 return "IP audio";
            case AudioDeviceInfo.TYPE_BUS:                return "Bus";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:      return "Remote submix";
            default:
                if (Build.VERSION.SDK_INT >= 28 && t == AudioDeviceInfo.TYPE_HEARING_AID) return "Hearing aid";
                if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) return "Bluetooth LE headset";
                if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_SPEAKER) return "Bluetooth LE speaker";
                return "Audio device (type " + t + ")";
        }
    }

    static String typeSlug(int t) {
        return friendlyType(t).toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static JSArray intArray(int[] a) {
        JSArray out = new JSArray();
        if (a != null) for (int v : a) out.put(v);
        return out;
    }
}
