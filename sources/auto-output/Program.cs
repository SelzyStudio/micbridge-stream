using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Management;
using System.Security.Principal;
using System.Threading;
using ApolloMic.Audio;

namespace MicBridge.AutoOutput {
  public sealed class WindowsOutput : IOutputAudio {
    public DefaultEndpoint[] Defaults() { return CoreAudio.Defaults().Where(x => x.flowIndex == 0).ToArray(); }
    public Endpoint[] Endpoints() { return CoreAudio.Endpoints().Where(x => x.flowIndex == 0).ToArray(); }
    public void Change(DefaultEndpoint[] rows) {
      if (rows.Any(x => x.flowIndex != 0)) throw new InvalidOperationException("Only render changes are permitted");
      CoreAudio.ChangeDefaults(rows);
    }
  }
  public static class Program {
    static string home;
    static int cachedPid;
    static DateTime cachedBorn;
    static string Identity { get { return WindowsIdentity.GetCurrent().User.Value.Replace('-', '_'); } }
    static void Log(string message) {
      try {
        string path = Path.Combine(home, "switch.log");
        if (File.Exists(path) && new FileInfo(path).Length > 128 * 1024) File.Move(path, path + "." + DateTime.UtcNow.Ticks + ".old");
        File.AppendAllText(path, DateTime.UtcNow.ToString("o") + " " + message + Environment.NewLine);
      } catch { }
    }
    static int HostPid() {
      var processes = Process.GetProcessesByName("sunshine");
      try { return processes.Length == 1 ? processes[0].Id : 0; }
      finally { foreach (var p in processes) p.Dispose(); }
    }
    static DateTime HostBorn(int pid) {
      if (pid == 0) { cachedPid = 0; cachedBorn = DateTime.MinValue; return DateTime.MinValue; }
      if (pid == cachedPid) return cachedBorn;
      using (var query = new ManagementObjectSearcher("SELECT CreationDate FROM Win32_Process WHERE ProcessId=" + pid))
      using (var rows = query.Get()) {
        foreach (ManagementObject row in rows) {
          using (row) {
            string value = row["CreationDate"] as string;
            if (value != null) {
              cachedBorn = ManagementDateTimeConverter.ToDateTime(value).ToUniversalTime(); cachedPid = pid; return cachedBorn;
            }
          }
        }
      }
      throw new IOException("Cannot validate Apollo process creation time");
    }
    [STAThread]
    public static int Main(string[] args) {
      home = AppDomain.CurrentDomain.BaseDirectory;
      if (args.Length == 2 && args[0] == "--self-test") return OutputTests.Run(args[1]);
      if (args.Length == 2 && args[0] == "--snapshot") {
        try { Files.Write(args[1], new { capturedUtc = DateTime.UtcNow.ToString("o"), defaults = CoreAudio.Defaults(), endpoints = CoreAudio.Endpoints() }); return 0; }
        catch (Exception e) { Log("snapshot error: " + e.Message); return 1; }
      }
      string stopName = @"Local\MicBridgeAutoOutputStop_" + Identity;
      if (args.Length == 1 && args[0] == "--stop") {
        try { using (var signal = EventWaitHandle.OpenExisting(stopName)) signal.Set(); return 0; }
        catch (WaitHandleCannotBeOpenedException) { return 0; }
      }
      bool created;
      using (var mutex = new Mutex(true, @"Local\MicBridgeAutoOutput_" + Identity, out created)) {
        if (!created) return 3;
        OutputRouting output = null;
        try {
          output = new OutputRouting(new WindowsOutput(), home, Log);
          if (args.Length == 1 && args[0] == "--restore") return output.RestoreNow() ? 0 : 2;
          if (args.Length != 2 || args[0] != "--watch") throw new ArgumentException("Usage: --watch <Apollo sunshine.log>");
          var detector = new ApolloLog(Path.GetFullPath(args[1]));
          var clock = Stopwatch.StartNew(); var debounce = new Debounce();
          long statusAt = 0; bool previousActive = false; string previousError = null;
          using (var stop = new EventWaitHandle(false, EventResetMode.ManualReset, stopName)) {
            Log("watcher started: output defaults only; microphone helper unchanged");
            while (!stop.WaitOne(250)) {
              bool active = false; string error = null;
              try { int pid = HostPid(); active = detector.Read(pid, HostBorn(pid)); }
              catch (Exception e) { error = e.Message; }
              long now = clock.ElapsedMilliseconds;
              try { output.Tick(active, debounce.RestoreDue(active, now), now); }
              catch (Exception e) { error = e.Message; }
              error = error ?? output.Error;
              if (error != previousError) { if (error != null) Log("error: " + error); previousError = error; }
              if (now >= statusAt || active != previousActive) {
                try {
                  Files.Write(Path.Combine(home, "status.json"), new {
                    checkedAtUtc = DateTime.UtcNow.ToString("o"), active = active,
                    outputOverrideApplied = output.Applied, restorationPending = output.RestorationPending,
                    baselineReady = output.BaselineReady, error = error, watcherPid = Process.GetCurrentProcess().Id,
                    restoreDelayMs = Debounce.RestoreDelayMs, outputDefaults = new WindowsOutput().Defaults()
                  });
                } catch (Exception e) { Log("status unavailable: " + e.Message); }
                statusAt = now + 5000;
              }
              previousActive = active;
            }
          }
          return 0;
        } catch (Exception e) { Log("fatal: " + e.Message); return 1; }
        finally {
          if (output != null) try { if (!output.RestoreNow()) Log("shutdown restoration remains pending"); }
            catch (Exception e) { Log("shutdown restoration pending: " + e.Message); }
          mutex.ReleaseMutex();
        }
      }
    }
  }
}
