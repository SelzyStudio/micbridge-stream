using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ApolloMic.Audio;

namespace MicBridge.AutoInput {
  public sealed class FakeInput : IInputAudio {
    public string[] Roles = { "rode", "usb", "communications" };
    public HashSet<string> Available = new HashSet<string>(new [] { "rode", "usb", "communications", "steam" });
    public List<string> Writes = new List<string>();
    public bool FailChange;
    public DefaultEndpoint[] Defaults() {
      return Enumerable.Range(0, 3).Select(i => new DefaultEndpoint { flowIndex = 1, roleIndex = i, deviceId = Roles[i], name = Roles[i] }).ToArray();
    }
    public Endpoint[] Endpoints() {
      return Available.Select(x => new Endpoint { flowIndex = 1, state = 1, deviceId = x, name = x == "steam" ? "Microphone (Steam Streaming Microphone)" : x }).ToArray();
    }
    public void Change(DefaultEndpoint[] rows) {
      if (rows.Any(x => x.flowIndex != 1)) throw new Exception("Playback mutation refused");
      if (FailChange) throw new Exception("Fake endpoint write failure");
      foreach (var row in rows) { Roles[row.roleIndex] = row.deviceId; Writes.Add(row.deviceId); }
    }
  }
  public static class Tests {
    static List<string> passed = new List<string>();
    static void Check(bool condition, string label) { if (!condition) throw new Exception(label); passed.Add(label); }
    public static int Run(string output) {
      string temp = Path.Combine(Path.GetTempPath(), "MicBridge-AutoInput-Tests-" + Guid.NewGuid().ToString("N"));
      Directory.CreateDirectory(temp);
      try {
        string path = Path.Combine(temp, "saved.json");
        var audio = new FakeInput(); var lease = new InputLease(audio, path, delegate {});
        Check(lease.Exit() && audio.Writes.Count == 0, "idle startup does not change defaults");
        lease.Enter();
        Check(audio.Roles.All(x => x == "steam") && File.Exists(path), "connect switches all three capture roles and persists original");
        lease.Enter(); lease.Exit();
        Check(audio.Roles.SequenceEqual(new [] { "rode", "usb", "communications" }), "duplicate connect preserves role-specific originals");
        Check(!File.Exists(path), "successful restore clears journal");
        lease.Enter(); audio.Roles[0] = "usb"; lease.Exit();
        Check(audio.Roles.SequenceEqual(new [] { "usb", "usb", "communications" }), "manual change during session preserved");
        lease.Enter(); var restarted = new InputLease(audio, path, delegate {}); restarted.Exit();
        Check(audio.Roles.SequenceEqual(new [] { "usb", "usb", "communications" }), "watcher crash recovery restores saved defaults");
        lease = new InputLease(audio, path, delegate {}); lease.Enter(); audio.Available.Remove("usb");
        Check(!lease.Exit() && File.Exists(path), "unplugged original retains recovery journal");
        audio.Available.Add("usb"); Check(lease.Exit() && audio.Roles[0] == "usb", "replug completes pending restoration");
        audio.Available.Remove("steam"); bool refused = false;
        try { lease.Enter(); } catch (InvalidOperationException) { refused = true; }
        Check(refused && !File.Exists(path), "missing Steam microphone makes no change");
        audio.Available.Add("steam"); audio.FailChange = true;
        try { lease.Enter(); } catch (Exception) {}
        Check(File.Exists(path), "endpoint failure retains original journal");
        audio.FailChange = false; lease.Exit();
        File.WriteAllText(path, "{}"); refused = false;
        try { new InputLease(audio, path, delegate {}); } catch (InvalidDataException) { refused = true; }
        Check(refused, "corrupt journal cannot change audio"); File.Delete(path);
        var session = new SessionState();
        session.Consume("Session resuming for app [Desktop]."); session.Consume("CLIENT CONNECTED");
        Check(session.Active, "real connect lifecycle recognized");
        session.Consume("Client media paused for [phone]"); Check(session.Active, "background media pause keeps routing active");
        session.Consume("CLIENT CONNECTED"); session.Consume("CLIENT DISCONNECTED");
        Check(session.Active, "one of multiple clients leaving does not restore");
        session.Consume("CLIENT DISCONNECTED"); Check(session.Active, "ENet disconnect alone cannot race a pending handoff");
        session.Consume("Session pausing for app [Desktop]."); Check(!session.Active, "final-session pause requests restore");
        session.Consume("CLIENT CONNECTED"); session.Consume("Session pausing for app [Desktop].");
        Check(!session.Active && session.Peers == 0, "timeout cleanup resets stale ENet peer count");
        session.Consume("CLIENT CONNECTED"); session.Consume("Terminating app [Desktop] when all clients are disconnected. Pause commands are skipped.");
        Check(!session.Active, "terminate-on-pause cleanup recognized");
        session.Consume("CLIENT CONNECTED"); session.Consume("Process terminated"); Check(!session.Active, "app exit cleanup recognized");
        session.Consume("CLIENT CONNECTED"); session.Consume("Apollo version: new"); Check(!session.Active, "new host epoch clears stale sessions");
        var debounce = new Debounce();
        Check(!debounce.RestoreDue(false, 0) && !debounce.RestoreDue(false, 1999), "brief interruption delays restoration");
        Check(!debounce.RestoreDue(true, 1900) && !debounce.RestoreDue(false, 2100), "fast reconnect cancels pending restore");
        Check(debounce.RestoreDue(false, 4100), "confirmed disconnect restores after two seconds");
        var logPath = Path.Combine(temp, "sunshine.log");
        File.WriteAllText(logPath, "[now]: Info: Apollo version: 1\n[now]: Info: Session resuming for app [Desktop].\n[now]: Info: CLIENT CONNECTED\n");
        var detector = new ApolloLog(logPath);
        Check(detector.Read(1), "real log format replay detects an active stream");
        Check(!detector.Read(0), "host process exit requests restoration");
        File.WriteAllText(logPath, "[later]: Info: Apollo version: 2\n");
        Check(!detector.Read(2), "log rotation starts disconnected");
        File.AppendAllText(logPath, "[later]: Info: CLIENT CONNECTED\n"); Check(detector.Read(2), "new host can connect after rotation");
        File.AppendAllText(logPath, "[later]: Info: Session pausing for app [Desktop].\n"); Check(!detector.Read(2), "real log replay observes last-session cleanup");
        File.WriteAllText(logPath, "[2026-10-27 12:00:00.000]: Info: Apollo version: old\n[2026-10-27 12:00:01.000]: Info: CLIENT CONNECTED\n");
        Check(!new ApolloLog(logPath).Read(3, new DateTime(2026, 10, 28, 12, 0, 0, DateTimeKind.Local).ToUniversalTime()), "old active logfile cannot activate a newly created host");
        audio.Roles = new [] { "rode", "usb", "communications" };
        lease = new InputLease(audio, path, delegate {}); lease.Enter();
        audio.Available.Remove("steam"); Check(!lease.TargetAvailable(), "target disappearance detected");
        audio.Roles = new [] { "rode", "rode", "rode" }; audio.Available.Add("steam");
        Check(lease.TargetAvailable(), "Steam reappearance detected"); lease.Enter(); lease.Exit();
        Check(audio.Roles.SequenceEqual(new [] { "rode", "usb", "communications" }), "target hotplug recovery preserves original snapshot");
        Check(audio.Writes.All(x => audio.Available.Contains(x)), "all test mutations stay within capture endpoints");
        audio = new FakeInput(); lease = new InputLease(audio, path, delegate {}); lease.Enter();
        audio.Available.Remove("steam"); audio.Roles = new [] { "usb", "usb", "usb" }; lease.Exit();
        Check(audio.Roles.SequenceEqual(new [] { "rode", "usb", "communications" }), "Steam disappearance with automatic fallback still restores originals");
        audio = new FakeInput(); lease = new InputLease(audio, path, delegate {}); lease.Enter();
        audio.Available.Remove("usb"); lease.Exit(); audio.Roles[0] = "communications";
        lease.Enter(); audio.Available.Add("usb"); lease.Exit();
        Check(audio.Roles[0] == "communications", "new session rebases completed roles while retaining unavailable originals");
        File.WriteAllText(logPath, "[2026-10-28 12:00:00.000]: Info: Apollo version: old\n[2026-10-28 12:00:00.100]: Info: CLIENT CONNECTED\n");
        Check(!new ApolloLog(logPath).Read(3, new DateTime(2026,10,28,12,0,1,DateTimeKind.Local).ToUniversalTime()), "one-second stale host epoch is rejected");
        audio = new FakeInput(); lease = new InputLease(audio, path, delegate {}); lease.Enter();
        audio.Roles[0] = "usb"; lease.ObserveManualChoices(); audio.Available.Remove("steam");
        audio.Roles[0] = "rode"; lease.TargetAvailable(); lease.Exit();
        Check(audio.Roles[0] == "usb", "observed manual preference survives later automatic fallback");
        audio = new FakeInput(); lease = new InputLease(audio, path, delegate {}); lease.Enter();
        audio.Available.Remove("usb"); lease.Exit();
        audio.Available.Remove("steam"); audio.Roles[1] = "rode"; lease.TargetAvailable(); lease.Exit();
        audio.Available.Add("steam"); lease.Enter(); audio.Available.Add("usb"); lease.Exit();
        Check(audio.Roles[1] == "usb", "pending original survives target interruption between sessions");
        File.WriteAllText(logPath, "[2026-10-28 12:00:01.000]: Info: Apollo version: new\n[2026-10-28 12:00:01.100]: Info: CLIENT CONNECTED\n");
        Check(new ApolloLog(logPath).Read(4, new DateTime(2026,10,28,12,0,1,DateTimeKind.Local).ToUniversalTime().AddTicks(9999)), "submillisecond log timestamp rounding accepts the correct host");
        Files.Write(output, new { success = true, passed = passed.Count, tests = passed }); return 0;
      } catch (Exception e) { Files.Write(output, new { success = false, passed = passed.Count, error = e.ToString() }); return 1; }
      finally { foreach (string file in Directory.GetFiles(temp)) File.Delete(file); Directory.Delete(temp); }
    }
  }
}
