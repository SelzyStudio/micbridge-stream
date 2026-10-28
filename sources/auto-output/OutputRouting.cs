using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ApolloMic.Audio;

namespace MicBridge.AutoOutput {
  public interface IOutputAudio {
    DefaultEndpoint[] Defaults();
    Endpoint[] Endpoints();
    void Change(DefaultEndpoint[] rows);
  }
  public sealed class OutputBaseline {
    public int Version = 1;
    public DefaultEndpoint[] Defaults;
  }
  public sealed class OutputJournal {
    public int Version = 1;
    public string Target;
    public DefaultEndpoint[] Original;
    public DefaultEndpoint[] Desired;
    public bool Restoring;
    public bool TargetInterrupted;
    public int[] NormalizedRoles = new int[0];
  }
  // This lease is deliberately separate from the microphone lease and its journal.
  public sealed class OutputRouting {
    readonly IOutputAudio audio;
    readonly string baselinePath, journalPath;
    readonly Action<string> log;
    OutputBaseline baseline;
    OutputJournal saved;
    bool blocked, recovering, lastActive;
    string persistedJournal;
    long lastNow, guardSince = -1, stableSince = -1;
    public bool Applied { get; private set; }
    public bool RestorationPending { get { return saved != null; } }
    public bool BaselineReady { get { return baseline != null || saved != null; } }
    public string Error { get; private set; }

    public OutputRouting(IOutputAudio adapter, string folder, Action<string> logger) {
      audio = adapter; log = logger ?? delegate {};
      baselinePath = Path.Combine(folder, "output-baseline.json");
      journalPath = Path.Combine(folder, "original-output.json");
      try {
        if (File.Exists(baselinePath)) {
          baseline = Files.Json.Deserialize<OutputBaseline>(File.ReadAllText(baselinePath));
          if (baseline == null || baseline.Version != 1) throw new InvalidDataException("Unsupported output baseline");
          ValidateRows(baseline.Defaults);
          if (baseline.Defaults.Any(IsSteam)) throw new InvalidDataException("Steam cannot be the preconnection output baseline");
        }
        if (File.Exists(journalPath)) {
          saved = Files.Json.Deserialize<OutputJournal>(File.ReadAllText(journalPath));
          if (saved == null || saved.Version != 1 || String.IsNullOrEmpty(saved.Target)) throw new InvalidDataException("Invalid output recovery journal");
          ValidateRows(saved.Original); ValidateRows(saved.Desired);
          if (saved.NormalizedRoles == null) saved.NormalizedRoles = new int[0];
          if (saved.NormalizedRoles.Any(x => x < 0 || x > 2) || saved.NormalizedRoles.Distinct().Count() != saved.NormalizedRoles.Length)
            throw new InvalidDataException("Invalid normalized output roles");
          if (saved.Original.Any(IsSteam)) throw new InvalidDataException("Invalid Steam original output");
          recovering = true;
        }
      } catch (Exception e) { blocked = true; Error = "Output recovery data rejected: " + e.Message; }
    }
    static void ValidateRows(DefaultEndpoint[] rows) {
      if (rows == null || rows.Length != 3 || rows.Any(x => x == null || x.flowIndex != 0 ||
          x.roleIndex < 0 || x.roleIndex > 2 || String.IsNullOrEmpty(x.deviceId)) ||
          rows.Select(x => x.roleIndex).Distinct().Count() != 3)
        throw new InvalidDataException("Exactly three distinct render roles are required");
    }
    static DefaultEndpoint Copy(DefaultEndpoint row) {
      return new DefaultEndpoint { flowIndex = 0, roleIndex = row.roleIndex, flow = "Render", role = row.role,
        deviceId = row.deviceId, name = row.name };
    }
    static DefaultEndpoint[] Copies(DefaultEndpoint[] rows) { return rows.Select(Copy).OrderBy(x => x.roleIndex).ToArray(); }
    static bool IsSteam(DefaultEndpoint row) { return (row.name ?? "").IndexOf("Steam Streaming", StringComparison.OrdinalIgnoreCase) >= 0; }
    static bool IsSpeaker(Endpoint row) {
      string name = row.name ?? "";
      return row.flowIndex == 0 && row.state == 1 && name.IndexOf("Steam Streaming Speakers", StringComparison.OrdinalIgnoreCase) >= 0 &&
        name.IndexOf("Steam Streaming Microphone", StringComparison.OrdinalIgnoreCase) < 0;
    }
    DefaultEndpoint[] ReadDefaults() {
      var rows = audio.Defaults(); ValidateRows(rows); return Copies(rows);
    }
    void Change(DefaultEndpoint[] rows) {
      if (rows.Any(x => x.flowIndex != 0)) throw new InvalidOperationException("Only render output changes are permitted");
      if (rows.Length != 0) audio.Change(rows);
    }
    void Save() {
      string value = Files.Json.Serialize(saved);
      // An unchanged guard observation does not require another disk flush.
      if (value == persistedJournal && File.Exists(journalPath)) return;
      Files.Write(journalPath, saved); persistedJournal = value;
    }
    void RememberIdle(DefaultEndpoint[] current, Endpoint[] endpoints) {
      // Apollo selects its virtual sink before CLIENT CONNECTED reaches the log.
      // Never replace the last physical baseline with that already-switched sink.
      if (current.Any(x => IsSteam(x) || endpoints.Any(e => e.deviceId == x.deviceId &&
          (e.name ?? "").IndexOf("Steam Streaming", StringComparison.OrdinalIgnoreCase) >= 0))) return;
      if (baseline == null || !baseline.Defaults.OrderBy(x => x.roleIndex).Select(x => x.deviceId).SequenceEqual(current.Select(x => x.deviceId))) {
        var candidate = new OutputBaseline { Defaults = Copies(current) };
        Files.Write(baselinePath, candidate); baseline = candidate;
      }
    }
    void Enter(DefaultEndpoint[] current, Endpoint[] endpoints) {
      var speakers = endpoints.Where(IsSpeaker).ToArray();
      if (speakers.Length != 1) throw new InvalidOperationException("Exactly one active Steam Streaming Speakers render endpoint is required");
      if (saved == null) {
        if (baseline == null) throw new InvalidOperationException("No preconnection output baseline; disconnect once before enabling output switching");
        saved = new OutputJournal { Target = speakers[0].deviceId, Original = Copies(baseline.Defaults), Desired = Copies(baseline.Defaults) };
      }
      if (saved.Restoring) saved.Original = Copies(saved.Desired);
      saved.Target = speakers[0].deviceId; saved.Restoring = false; saved.TargetInterrupted = false;
      saved.NormalizedRoles = new int[0];
      // Persist originals before the first Windows output mutation, including a retry after a partial failure.
      Save();
      Change(current.Where(x => x.deviceId != saved.Target).Select(x => new DefaultEndpoint {
        flowIndex = 0, roleIndex = x.roleIndex, flow = "Render", role = x.role, deviceId = saved.Target, name = speakers[0].name }).ToArray());
      Applied = true; recovering = false; guardSince = stableSince = -1;
      log("connected: Steam speakers selected; preconnection render roles retained");
    }
    public void Tick(bool active, bool restoreDue, long nowMs) {
      if (blocked) return;
      lastNow = nowMs;
      try {
        var current = ReadDefaults(); var endpoints = audio.Endpoints();
        if (active) {
          bool targetAvailable = saved != null && endpoints.Any(x => IsSpeaker(x) && x.deviceId == saved.Target);
          if (saved != null && !targetAvailable) {
            if (!saved.TargetInterrupted) { saved.TargetInterrupted = true; Save(); }
            Applied = false;
          }
          if (!Applied || !lastActive && saved != null && saved.Restoring) Enter(current, endpoints);
        } else if (saved != null) {
          if (restoreDue || saved.Restoring || recovering) RestoreTick(current, endpoints, nowMs, false);
        } else {
          Applied = false; RememberIdle(current, endpoints);
        }
        Error = null;
      } catch (Exception e) { Error = e.Message; }
      lastActive = active;
    }
    bool RestoreTick(DefaultEndpoint[] current, Endpoint[] endpoints, long nowMs, bool immediate) {
      if (saved == null) return true;
      if (!saved.Restoring) { saved.Restoring = true; Save(); }
      if (guardSince < 0) guardSince = nowMs;
      if (!endpoints.Any(x => IsSpeaker(x) && x.deviceId == saved.Target) && !saved.TargetInterrupted) {
        saved.TargetInterrupted = true; Save();
      }
      string originalConsole = saved.Original.Single(x => x.roleIndex == 0).deviceId;
      var changes = new List<DefaultEndpoint>(); bool unavailable = false;
      var normalized = saved.NormalizedRoles.ToList();
      foreach (var desired in saved.Desired) {
        var row = current.Single(x => x.roleIndex == desired.roleIndex);
        if (row.deviceId == desired.deviceId) { if (!normalized.Contains(row.roleIndex)) normalized.Add(row.roleIndex); continue; }
        bool fallback = saved.TargetInterrupted && !normalized.Contains(row.roleIndex);
        bool hostTransition = row.deviceId == originalConsole || row.deviceId == saved.Target;
        if (!hostTransition && !fallback) {
          // Apollo changes its three roles sequentially: a mixed Console/Steam/original
          // snapshot is still host cleanup, not a manual choice. A deliberate manual
          // selection of the original Console endpoint during this guard is inherently
          // indistinguishable from that cleanup and is restored to the saved role.
          desired.deviceId = row.deviceId; desired.name = row.name;
          log("restore: an independent manual render choice was preserved");
          if (!normalized.Contains(row.roleIndex)) normalized.Add(row.roleIndex);
          continue;
        }
        if (!endpoints.Any(x => x.flowIndex == 0 && x.state == 1 && x.deviceId == desired.deviceId)) { unavailable = true; continue; }
        changes.Add(Copy(desired));
      }
      saved.NormalizedRoles = normalized.OrderBy(x => x).ToArray();
      Save();
      if (changes.Count != 0) { Change(changes.ToArray()); stableSince = -1; }
      Applied = false; recovering = false;
      var after = ReadDefaults();
      saved.NormalizedRoles = saved.NormalizedRoles.Concat(saved.Desired.Where(x => after.Any(y => y.roleIndex == x.roleIndex && y.deviceId == x.deviceId)).Select(x => x.roleIndex)).Distinct().OrderBy(x => x).ToArray();
      Save();
      bool matches = saved.Desired.All(x => after.Any(y => y.roleIndex == x.roleIndex && y.deviceId == x.deviceId));
      if (unavailable || !matches) { stableSince = -1; return false; }
      if (stableSince < 0) stableSince = nowMs;
      bool distinct = saved.Original.Select(x => x.deviceId).Distinct().Count() > 1 || saved.Desired.Select(x => x.deviceId).Distinct().Count() > 1;
      long guardMs = distinct ? 35000 : 2000;
      if (!immediate && (nowMs - guardSince < guardMs || nowMs - stableSince < 2000)) return false;
      RememberIdle(after, endpoints);
      // Commit the new idle baseline before removing the only durable record of a
      // preserved manual choice. A failed baseline write must leave recovery intact.
      File.Delete(journalPath); saved = null; persistedJournal = null; guardSince = stableSince = -1;
      log("disconnected: original render roles restored; output recovery journal completed");
      return true;
    }
    public bool RestoreNow() {
      if (blocked) return false;
      try {
        bool done = RestoreTick(ReadDefaults(), audio.Endpoints(), lastNow, true); Error = null; return done;
      } catch (Exception e) { Error = e.Message; return false; }
    }
  }
}
