using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Management;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using ApolloMic.Audio;

namespace MicBridge.AutoInput {
  public sealed class SavedInput {
    public int Version = 1;
    public string Target;
    public string SavedAtUtc;
    public DefaultEndpoint[] Original;
    public bool RestorationStarted;
    public int[] PendingRoles;
    public DefaultEndpoint[] ManualOverrides;
    public bool TargetInterrupted;
  }
  public interface IInputAudio {
    DefaultEndpoint[] Defaults();
    Endpoint[] Endpoints();
    void Change(DefaultEndpoint[] rows);
  }
  public sealed class WindowsInput : IInputAudio {
    public DefaultEndpoint[] Defaults() { return CoreAudio.Defaults().Where(x => x.flowIndex == 1).ToArray(); }
    public Endpoint[] Endpoints() { return CoreAudio.Endpoints().Where(x => x.flowIndex == 1).ToArray(); }
    public void Change(DefaultEndpoint[] rows) {
      if (rows.Any(x => x.flowIndex != 1)) throw new InvalidOperationException("Only capture changes are permitted");
      CoreAudio.ChangeDefaults(rows);
    }
  }
  public static class Files {
    public static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    public static void Write(string path, object data) {
      string temp = path + ".tmp";
      byte[] bytes = new UTF8Encoding(false).GetBytes(Json.Serialize(data));
      using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None)) {
        stream.Write(bytes, 0, bytes.Length); stream.Flush(true);
      }
      if (File.Exists(path)) File.Replace(temp, path, null);
      else File.Move(temp, path);
    }
  }
  // Policy seam used both by the real Windows adapter and regression tests.
  public sealed class InputLease {
    readonly IInputAudio audio;
    readonly string journal;
    readonly Action<string> log;
    public SavedInput Saved { get; private set; }
    public InputLease(IInputAudio adapter, string path, Action<string> logger) {
      audio = adapter; journal = path; log = logger;
      if (File.Exists(journal)) {
        Saved = Files.Json.Deserialize<SavedInput>(File.ReadAllText(journal));
        Validate(Saved);
      }
    }
    static void Validate(SavedInput s) {
      if (s == null || s.Version != 1 || String.IsNullOrEmpty(s.Target) || s.Original == null || s.Original.Length != 3 ||
          s.Original.Any(x => x.flowIndex != 1 || x.roleIndex < 0 || x.roleIndex > 2 || String.IsNullOrEmpty(x.deviceId)) ||
          s.Original.Select(x => x.roleIndex).Distinct().Count() != 3)
        throw new InvalidDataException("Invalid original-input journal; no default change permitted");
      if (s.PendingRoles == null) s.PendingRoles = new [] { 0, 1, 2 }; // Existing v1 journals.
      if (s.ManualOverrides == null) s.ManualOverrides = new DefaultEndpoint[0];
      if (s.PendingRoles.Any(x => x < 0 || x > 2) || s.PendingRoles.Distinct().Count() != s.PendingRoles.Length ||
          s.ManualOverrides.Any(x => x == null || x.flowIndex != 1 || x.roleIndex < 0 || x.roleIndex > 2 || String.IsNullOrEmpty(x.deviceId)) ||
          s.ManualOverrides.Select(x => x.roleIndex).Distinct().Count() != s.ManualOverrides.Length)
        throw new InvalidDataException("Invalid capture ownership journal");
    }
    static DefaultEndpoint Copy(DefaultEndpoint row, string id) {
      return new DefaultEndpoint { flowIndex = 1, roleIndex = row.roleIndex, deviceId = id, flow = "Capture", role = row.role, name = row.name };
    }
    public void Enter() {
      var candidates = audio.Endpoints().Where(x => x.flowIndex == 1 && x.state == 1 &&
          x.name.IndexOf("Steam Streaming Microphone", StringComparison.OrdinalIgnoreCase) >= 0).ToArray();
      if (candidates.Length != 1) throw new InvalidOperationException("Exactly one active Steam capture endpoint is required");
      if (Saved == null) {
        var original = audio.Defaults();
        var saved = new SavedInput { Target = candidates[0].deviceId, SavedAtUtc = DateTime.UtcNow.ToString("o"), Original = original };
        Validate(saved);
        // Journal MUST reach disk before the first device change. It survives a crash.
        Files.Write(journal, saved); Saved = saved;
      }
      if (Saved.Target != candidates[0].deviceId) throw new InvalidOperationException("Steam endpoint changed while restoration is pending");
      var current = audio.Defaults();
      if (Saved.RestorationStarted) {
        // A new logical connection must not reuse completed roles from an old pending restore.
        var rebased = current.Select(x => Saved.PendingRoles.Contains(x.roleIndex) && (x.deviceId == Saved.Target || Saved.TargetInterrupted)
          ? Saved.ManualOverrides.SingleOrDefault(m => m.roleIndex == x.roleIndex) ?? Saved.Original.Single(old => old.roleIndex == x.roleIndex) : x).ToArray();
        Saved = new SavedInput { Target = Saved.Target, SavedAtUtc = DateTime.UtcNow.ToString("o"), Original = rebased };
        Validate(Saved); Files.Write(journal, Saved);
      }
      audio.Change(current.Where(x => !Saved.ManualOverrides.Any(m => m.roleIndex == x.roleIndex)).Select(x => Copy(x, Saved.Target)).ToArray());
      Saved.TargetInterrupted = false;
      Files.Write(journal, Saved);
      log("connected: Steam microphone selected; original capture roles saved");
    }
    public bool TargetAvailable() {
      bool available = Saved != null && audio.Endpoints().Any(x => x.state == 1 && x.deviceId == Saved.Target);
      if (!available && Saved != null && !Saved.TargetInterrupted) { Saved.TargetInterrupted = true; Files.Write(journal, Saved); }
      return available;
    }
    public void ObserveManualChoices() {
      if (Saved == null || Saved.RestorationStarted || Saved.TargetInterrupted) return;
      if (!audio.Endpoints().Any(x => x.state == 1 && x.deviceId == Saved.Target)) return;
      var manual = audio.Defaults().Where(x => x.deviceId != Saved.Target && !String.IsNullOrEmpty(x.deviceId)).ToArray();
      if (Files.Json.Serialize(manual) != Files.Json.Serialize(Saved.ManualOverrides)) {
        Saved.ManualOverrides = manual; Files.Write(journal, Saved);
      }
    }
    public bool Exit() {
      if (Saved == null) return true;
      var defaults = audio.Defaults(); var endpoints = audio.Endpoints();
      bool targetPresent = endpoints.Any(x => x.state == 1 && x.deviceId == Saved.Target);
      if (!targetPresent) Saved.TargetInterrupted = true;
      if (targetPresent && !Saved.TargetInterrupted) ObserveManualChoices();
      Saved.RestorationStarted = true;
      Files.Write(journal, Saved);
      var wanted = new List<DefaultEndpoint>(); bool unavailable = false;
      var pending = new List<int>();
      foreach (var original in Saved.Original.Where(x => Saved.PendingRoles.Contains(x.roleIndex))) {
        var current = defaults.Single(x => x.roleIndex == original.roleIndex);
        // Only infer a manual selection while the target is still present, not after Windows fallback.
        if (targetPresent && !Saved.TargetInterrupted && current.deviceId != Saved.Target) continue;
        var selected = Saved.ManualOverrides.SingleOrDefault(x => x.roleIndex == original.roleIndex) ?? original;
        if (current.deviceId == selected.deviceId) continue;
        if (!endpoints.Any(x => x.state == 1 && x.deviceId == selected.deviceId)) { unavailable = true; pending.Add(original.roleIndex); continue; }
        wanted.Add(selected);
      }
      if (wanted.Count != 0) audio.Change(wanted.ToArray());
      Saved.PendingRoles = pending.ToArray();
      Files.Write(journal, Saved);
      if (unavailable) { log("restore waiting: original microphone is unplugged; journal retained"); return false; }
      File.Delete(journal); Saved = null;
      log("disconnected: original capture roles restored; manual changes preserved"); return true;
    }
  }
  public sealed class SessionState {
    public int Peers;
    public bool Active;
    public bool Seen;
    public void Consume(string message) {
      if (message.StartsWith("Apollo version:") || message.StartsWith("Sunshine version:")) { Peers = 0; Active = false; Seen = true; }
      else if (message.StartsWith("Session resuming for app [")) { Active = true; Seen = true; }
      else if (message == "CLIENT CONNECTED") { Peers++; Active = true; Seen = true; }
      // ENet teardown can race a replacement session. Wait for Apollo's final-session pause/exit marker.
      else if (message == "CLIENT DISCONNECTED") { Peers = Math.Max(0, Peers - 1); Seen = true; }
      else if (message.StartsWith("Session pausing for app [") || message.StartsWith("Session already stopped, do not run pause commands.") ||
          message == "Process terminated" || (message.StartsWith("Terminating app [") && message.Contains("when all clients are disconnected"))) {
        Peers = 0; Active = false; Seen = true;
      }
      // Client media paused/resumed is intentionally ignored (background app).
    }
  }
  public sealed class Debounce {
    public const int RestoreDelayMs = 2000;
    long inactiveAt = -1;
    public bool RestoreDue(bool active, long milliseconds) {
      if (active) { inactiveAt = -1; return false; }
      if (inactiveAt < 0) inactiveAt = milliseconds;
      return milliseconds - inactiveAt >= RestoreDelayMs;
    }
  }
  public sealed class ApolloLog {
    readonly string path;
    DateTime lastWrite = DateTime.MinValue;
    long lastSize = -1;
    int lastPid = -1;
    string prefix;
    public bool Active { get; private set; }
    public ApolloLog(string filename) { path = filename; }
    public bool Read(int pid) { return Read(pid, DateTime.MinValue); }
    public bool Read(int pid, DateTime bornUtc) {
      if (pid == 0) { Active = false; lastPid = 0; lastSize = -1; return false; }
      var info = new FileInfo(path); info.Refresh();
      if (!info.Exists) throw new IOException("Apollo log unavailable");
      if (pid == lastPid && info.Length == lastSize && info.LastWriteTimeUtc == lastWrite) return Active;
      // Bounded read, retried on the next tick if Apollo rotates the log concurrently.
      if (info.Length > 64L * 1024 * 1024) throw new IOException("Apollo log too large; safe restoration selected");
      string text;
      using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
      using (var reader = new StreamReader(stream, Encoding.UTF8, true)) text = reader.ReadToEnd();
      int newline = text.IndexOf('\n');
      string header = newline >= 0 ? text.Substring(0, newline) : text;
      if (bornUtc != DateTime.MinValue) {
        DateTime headerTime;
        if (header.Length < 25 || !DateTime.TryParseExact(header.Substring(1, 23), "yyyy-MM-dd HH:mm:ss.fff",
            CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out headerTime))
          throw new IOException("Cannot validate current Apollo log epoch");
        // Only accommodate sub-millisecond timestamp rounding, never another host's prior second.
        if (headerTime.ToUniversalTime() < bornUtc.AddMilliseconds(-2)) { Active = false; return false; }
      }
      // If a new PID has appeared but its new log has not yet been created, don't replay a stale active session.
      if (lastPid > 0 && pid != lastPid && header == prefix) { Active = false; return false; }
      var state = new SessionState();
      using (var reader = new StringReader(text)) {
        string line;
        while ((line = reader.ReadLine()) != null) {
          int marker = line.IndexOf("]: Info: ", StringComparison.Ordinal);
          if (marker >= 0) state.Consume(line.Substring(marker + 9));
        }
      }
      if (!state.Seen) throw new IOException("No supported Apollo session lifecycle markers");
      Active = state.Active; lastPid = pid; lastSize = info.Length; lastWrite = info.LastWriteTimeUtc; prefix = header;
      return Active;
    }
  }
  public static class Program {
    static string home, statusPath;
    static int cachedPid;
    static DateTime cachedBorn;
    static string Identity { get { return WindowsIdentity.GetCurrent().User.Value.Replace('-', '_'); } }
    static void Log(string message) {
      try {
      string path = Path.Combine(home, "switch.log");
      if (File.Exists(path) && new FileInfo(path).Length > 128 * 1024) File.Move(path, path + "." + DateTime.UtcNow.Ticks + ".old");
      File.AppendAllText(path, DateTime.UtcNow.ToString("o") + " " + message + Environment.NewLine);
      } catch { /* Diagnostic writes cannot terminate capture restoration. */ }
    }
    static int HostPid() {
      var processes = Process.GetProcessesByName("sunshine");
      try { return processes.Length == 1 ? processes[0].Id : 0; }
      finally { foreach (var process in processes) process.Dispose(); }
    }
    static DateTime HostBorn(int pid) {
      if (pid == 0) { cachedPid = 0; cachedBorn = DateTime.MinValue; return DateTime.MinValue; }
      if (pid == cachedPid) return cachedBorn;
      using (var query = new ManagementObjectSearcher("SELECT CreationDate FROM Win32_Process WHERE ProcessId=" + pid))
      using (var result = query.Get()) {
        foreach (ManagementObject row in result) {
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
      home = AppDomain.CurrentDomain.BaseDirectory; statusPath = Path.Combine(home, "status.json");
      if (args.Length > 0 && args[0] == "--self-test") return Tests.Run(args[1]);
      string eventName = @"Local\MicBridgeAutoInputStop_" + Identity;
      if (args.Length > 0 && args[0] == "--stop") {
        try { using (var signal = EventWaitHandle.OpenExisting(eventName)) signal.Set(); return 0; }
        catch (WaitHandleCannotBeOpenedException) { return 0; }
      }
      bool created;
      using (var mutex = new Mutex(true, @"Local\MicBridgeAutoInput_" + Identity, out created)) {
        if (!created) return 0;
        InputLease lease = null;
        try {
          lease = new InputLease(new WindowsInput(), Path.Combine(home, "original-input.json"), Log);
          if (args.Length > 0 && args[0] == "--restore") return lease.Exit() ? 0 : 2;
          if (args.Length != 2 || args[0] != "--watch") throw new ArgumentException("Usage: --watch <Apollo sunshine.log>");
          var detector = new ApolloLog(Path.GetFullPath(args[1]));
          var clock = Stopwatch.StartNew(); var debounce = new Debounce();
          bool applied = false, previousActive = false, targetMissing = false;
          long retryAt = 0, statusAt = 0, endpointCheckAt = 0;
          string lastError = null;
          using (var stop = new EventWaitHandle(false, EventResetMode.ManualReset, eventName)) {
            Log("watcher started: capture defaults only; no audio recording or playback changes");
            while (!stop.WaitOne(250)) {
              bool active = false; string error = null;
              try { int pid = HostPid(); active = detector.Read(pid, HostBorn(pid)); }
              catch (Exception e) { error = e.Message; }
              long now = clock.ElapsedMilliseconds;
              bool restore = debounce.RestoreDue(active, now);
              try {
                if (active && applied && now >= endpointCheckAt) {
                  bool available = lease.TargetAvailable();
                  if (!available) targetMissing = true;
                  else if (targetMissing) { applied = false; targetMissing = false; }
                  else lease.ObserveManualChoices();
                  endpointCheckAt = now + 1000;
                }
                if (active && !applied && now >= retryAt) { lease.Enter(); applied = true; }
                else if (restore && now >= retryAt) { applied = false; targetMissing = false; if (!lease.Exit()) retryAt = now + 2000; }
              } catch (Exception e) { error = e.Message; retryAt = now + 2000; }
              // Reconnect during grace keeps the original journal and the selected Steam device.
              if (error != lastError) { if (error != null) Log("error: " + error); lastError = error; }
              if (now >= statusAt || active != previousActive) {
                try {
                Files.Write(statusPath, new { checkedAtUtc = DateTime.UtcNow.ToString("o"), active = active,
                  captureOverrideApplied = applied, restorationPending = lease.Saved != null, error = error,
                  watcherPid = Process.GetCurrentProcess().Id, restoreDelayMs = Debounce.RestoreDelayMs,
                  inputDefaults = new WindowsInput().Defaults() });
                } catch (Exception e) { Log("status unavailable (watcher continues): " + e.Message); }
                statusAt = now + 5000;
              }
              previousActive = active;
            }
          }
          return 0;
        } catch (Exception e) { Log("fatal: " + e.Message); return 1; }
        finally {
          if (lease != null) try { lease.Exit(); } catch (Exception e) { Log("shutdown restore pending: " + e.Message); }
          mutex.ReleaseMutex();
        }
      }
    }
  }
}
