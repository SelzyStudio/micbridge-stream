
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
namespace ApolloMic.Audio {
  [StructLayout(LayoutKind.Sequential)] internal struct PropertyKey { public Guid format; public uint id; }
  [StructLayout(LayoutKind.Sequential)] internal struct PropVariant {
    public ushort type, reserved1, reserved2, reserved3; public IntPtr value, padding;
  }
  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int flow, uint state, out IDeviceCollection devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice device);
    [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IDevice device);
    [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
    [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
  }
  [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IDeviceCollection {
    [PreserveSig] int GetCount(out uint count);
    [PreserveSig] int Item(uint index, out IDevice device);
  }
  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IDevice {
    [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    [PreserveSig] int OpenPropertyStore(uint access, out IPropertyStore store);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetState(out uint state);
  }
  [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IPropertyStore {
    [PreserveSig] int GetCount(out uint count);
    [PreserveSig] int GetAt(uint index, out PropertyKey key);
    [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
    [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
    [PreserveSig] int Commit();
  }
  [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IAudioMeter {
    [PreserveSig] int GetPeakValue(out float peak);
    [PreserveSig] int GetMeteringChannelCount(out uint channels);
    [PreserveSig] int GetChannelsPeakValues(uint channels, IntPtr peaks);
    [PreserveSig] int QueryHardwareSupport(out uint mask);
  }
  [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  internal interface IPolicyConfig {
    [PreserveSig] int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string id, out IntPtr format);
    [PreserveSig] int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, int def, out IntPtr format);
    [PreserveSig] int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id);
    [PreserveSig] int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr endpoint, IntPtr mix);
    [PreserveSig] int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string id, int def, out long period, out long minimum);
    [PreserveSig] int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string id, ref long period);
    [PreserveSig] int GetShareMode([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr mode);
    [PreserveSig] int SetShareMode([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr mode);
    [PreserveSig] int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string id, ref PropertyKey key, out PropVariant value);
    [PreserveSig] int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string id, ref PropertyKey key, ref PropVariant value);
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
    [PreserveSig] int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)] string id, int visible);
  }
  public sealed class Endpoint {
    public int flowIndex; public string flow, deviceId, name; public uint state;
  }
  public sealed class DefaultEndpoint {
    public int flowIndex, roleIndex; public string flow, role, deviceId, name;
  }
  public sealed class MeterResult {
    public Endpoint endpoint; public int samples, positiveSamples; public double maxPeak, meanPeak;
    public string startedUtc, completedUtc;
  }
  public static class CoreAudio {
    static readonly string[] Flows = { "Render", "Capture" };
    static readonly string[] Roles = { "Console", "Multimedia", "Communications" };
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant value);
    static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }
    static void Release(object instance) { if (instance != null && Marshal.IsComObject(instance)) Marshal.ReleaseComObject(instance); }
    static IDeviceEnumerator Enumerator() {
      return (IDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"), true));
    }
    static Endpoint Describe(IDevice device, int flow) {
      string id; uint state; Check(device.GetId(out id)); Check(device.GetState(out state));
      string name = id; IPropertyStore store = null;
      try {
        Check(device.OpenPropertyStore(0, out store));
        var key = new PropertyKey { format = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), id = 14 };
        PropVariant value; Check(store.GetValue(ref key, out value));
        try { if (value.type == 31 && value.value != IntPtr.Zero) name = Marshal.PtrToStringUni(value.value); }
        finally { Check(PropVariantClear(ref value)); }
      } finally { Release(store); }
      return new Endpoint { flowIndex = flow, flow = Flows[flow], deviceId = id, name = name, state = state };
    }
    public static Endpoint[] Endpoints() {
      IDeviceEnumerator e = Enumerator(); var rows = new List<Endpoint>();
      try {
        for (int flow = 0; flow < 2; flow++) {
          IDeviceCollection devices = null;
          try {
            Check(e.EnumAudioEndpoints(flow, 1, out devices)); uint count; Check(devices.GetCount(out count));
            for (uint i = 0; i < count; i++) { IDevice d = null; try { Check(devices.Item(i, out d)); rows.Add(Describe(d, flow)); } finally { Release(d); } }
          } finally { Release(devices); }
        }
      } finally { Release(e); }
      return rows.ToArray();
    }
    public static DefaultEndpoint[] Defaults() {
      IDeviceEnumerator e = Enumerator(); var rows = new List<DefaultEndpoint>();
      try {
        for (int flow = 0; flow < 2; flow++) for (int role = 0; role < 3; role++) {
          IDevice device = null;
          var row = new DefaultEndpoint { flowIndex = flow, flow = Flows[flow], roleIndex = role, role = Roles[role] };
          try {
            int hr = e.GetDefaultAudioEndpoint(flow, role, out device);
            if (hr != unchecked((int)0x80070490)) { Check(hr); var d = Describe(device, flow); row.deviceId = d.deviceId; row.name = d.name; }
          } finally { Release(device); }
          rows.Add(row);
        }
      } finally { Release(e); }
      return rows.ToArray();
    }
    public static void ChangeDefaults(DefaultEndpoint[] wanted) {
      var active = Endpoints(); var previous = Defaults(); var keys = new HashSet<string>();
      foreach (var desired in wanted) {
        if (desired.flowIndex < 0 || desired.flowIndex > 1 || desired.roleIndex < 0 || desired.roleIndex > 2 ||
            !keys.Add(desired.flowIndex + ":" + desired.roleIndex)) throw new InvalidOperationException("Invalid or duplicate saved role.");
        bool found = false; foreach (var d in active) if (d.deviceId == desired.deviceId && d.flowIndex == desired.flowIndex) found = true;
        if (!found) throw new InvalidOperationException("A requested saved endpoint is unavailable; defaults were not changed.");
        foreach (var old in previous) if (old.flowIndex == desired.flowIndex && old.roleIndex == desired.roleIndex && String.IsNullOrEmpty(old.deviceId))
          throw new InvalidOperationException("A previous default is missing; reversible change refused.");
      }
      IPolicyConfig policy = (IPolicyConfig)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9"), true));
      var applied = new List<DefaultEndpoint>();
      try {
        foreach (var desired in wanted) {
          foreach (var old in previous) if (old.flowIndex == desired.flowIndex && old.roleIndex == desired.roleIndex) applied.Add(old);
          Check(policy.SetDefaultEndpoint(desired.deviceId, desired.roleIndex));
        }
        foreach (var desired in wanted) foreach (var actual in Defaults())
          if (actual.flowIndex == desired.flowIndex && actual.roleIndex == desired.roleIndex && actual.deviceId != desired.deviceId)
            throw new InvalidOperationException("Default endpoint verification failed.");
      } catch (Exception failure) {
        var recovery = new List<string>();
        for (int i = applied.Count - 1; i >= 0; i--) { int hr = policy.SetDefaultEndpoint(applied[i].deviceId, applied[i].roleIndex); if (hr < 0) recovery.Add(hr.ToString("X8")); }
        if (recovery.Count > 0) throw new InvalidOperationException("Default change failed and rollback needs review. HRESULTs: " + String.Join(",", recovery.ToArray()), failure);
        throw;
      } finally { Release(policy); }
    }
  }
}
