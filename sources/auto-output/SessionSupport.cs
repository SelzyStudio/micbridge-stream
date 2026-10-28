using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Web.Script.Serialization;
namespace MicBridge.AutoOutput {
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
}
