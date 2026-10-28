using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MicBridge.AutoInput;

class Adversarial {
  static object Capture(string label, Func<object> run) {
    try { return new { label=label, result=run(), exception=(string)null }; }
    catch(Exception e) { return new { label=label, result=(object)null, exception=e.ToString() }; }
  }
  static int Main(string[] args) {
    string home = AppDomain.CurrentDomain.BaseDirectory;
    var cases = new List<object>();
    Action<string> log = delegate {};
    cases.Add(Capture("partial restore followed by two reconnect cycles", delegate {
      string path=Path.Combine(home,"partial-journal.json"); if(File.Exists(path))File.Delete(path);
      var audio=new FakeInput(); var lease=new InputLease(audio,path,log);
      lease.Enter(); audio.Available.Remove("usb"); bool first=lease.Exit();
      var partiallyRestored=audio.Roles.ToArray();
      lease.Enter(); lease.Exit(); lease.Enter(); audio.Available.Add("usb"); bool final=lease.Exit();
      return new { firstExit=first, partiallyRestored=partiallyRestored, finalExit=final, finalRoles=audio.Roles, pass=audio.Roles.SequenceEqual(new[]{"rode","usb","communications"}) };
    }));
    cases.Add(Capture("user changes an already restored role while another role remains pending", delegate {
      string path=Path.Combine(home,"idle-edit-journal.json"); if(File.Exists(path))File.Delete(path);
      var audio=new FakeInput(); var lease=new InputLease(audio,path,log);
      lease.Enter(); audio.Available.Remove("usb"); lease.Exit();
      audio.Roles[0]="communications"; // User chooses an available different mic while disconnected.
      var beforeSecondConnect=audio.Roles.ToArray();
      lease.Enter(); audio.Available.Add("usb"); lease.Exit();
      return new { expectedRole0="communications", actualRole0=audio.Roles[0], beforeSecondConnect=beforeSecondConnect, finalRoles=audio.Roles, pass=audio.Roles[0]=="communications" };
    }));
    cases.Add(Capture("Steam disappeared and Windows selected another endpoint before disconnect", delegate {
      string path=Path.Combine(home,"target-missing-journal.json"); if(File.Exists(path))File.Delete(path);
      var audio=new FakeInput(); var lease=new InputLease(audio,path,log);
      lease.Enter(); audio.Available.Remove("steam"); audio.Roles=new[]{"usb","usb","usb"};
      bool done=lease.Exit();
      return new { expectedRole0="rode",actualRole0=audio.Roles[0], exitComplete=done, journalRetained=File.Exists(path), pass=audio.Roles[0]=="rode", note="No explicit user edit occurred; fallback only" };
    }));
    cases.Add(Capture("pending original survives a Steam disappearance between sessions", delegate {
      string path=Path.Combine(home,"pending-fallback-journal.json"); if(File.Exists(path))File.Delete(path);
      var audio=new FakeInput(); var lease=new InputLease(audio,path,log);
      lease.Enter(); audio.Available.Remove("usb"); lease.Exit();
      audio.Available.Remove("steam"); audio.Roles[1]="rode"; // Automatic fallback of still-pending Steam role.
      lease.TargetAvailable(); lease.Exit();
      audio.Available.Add("steam"); lease.Enter();
      audio.Available.Add("usb"); lease.Exit();
      return new { expectedRole1="usb",actualRole1=audio.Roles[1],pass=audio.Roles[1]=="usb" };
    }));
    cases.Add(Capture("manual selected microphone survives target interruption", delegate {
      string path=Path.Combine(home,"manual-target-journal.json"); if(File.Exists(path))File.Delete(path);
      var audio=new FakeInput(); var lease=new InputLease(audio,path,log);
      lease.Enter(); audio.Roles[0]="communications"; lease.ObserveManualChoices();
      audio.Available.Remove("steam"); lease.TargetAvailable();
      audio.Roles[1]="rode"; audio.Roles[2]="rode";
      audio.Available.Add("steam"); lease.Enter();
      var resumed=audio.Roles.ToArray(); lease.Exit();
      return new { resumed=resumed, finalRoles=audio.Roles,pass=audio.Roles.SequenceEqual(new[]{"communications","usb","communications"})&&resumed[0]=="communications" };
    }));
    cases.Add(Capture("log incomplete last marker is completed later", delegate {
      string path=Path.Combine(home,"incomplete.log");
      File.WriteAllText(path,"[now]: Info: Apollo version: test\n[now]: Info: CLIENT CONNECTED\n[now]: Info: Session paus");
      var reader=new ApolloLog(path); bool partial=reader.Read(1234);
      File.AppendAllText(path,"ing for app [Desktop].\n"); bool complete=reader.Read(1234);
      return new { partial=partial,complete=complete,pass=partial&&!complete };
    }));
    cases.Add(Capture("newline absent but log payload looks final", delegate {
      string path=Path.Combine(home,"prefix-pause.log");
      File.WriteAllText(path,"[now]: Info: Apollo version: test\n[now]: Info: CLIENT CONNECTED\n[now]: Info: Session pausing for app [");
      bool active=new ApolloLog(path).Read(1234);
      return new { active=active, note="Marker consumed before full line, but pause emission itself already implies final session",potentialPremature=(!active) };
    }));
    cases.Add(Capture("old active logfile from one second before new process creation", delegate {
      string path=Path.Combine(home,"old-epoch.log");
      File.WriteAllText(path,"[2026-10-28 12:00:00.000]: Info: Apollo version: old\n[2026-10-28 12:00:00.100]: Info: CLIENT CONNECTED\n");
      bool active=new ApolloLog(path).Read(1234,new DateTime(2026,10,28,12,0,1,DateTimeKind.Local).ToUniversalTime());
      return new { active=active, pass=!active, note="Reject the previous process log when the host restarts quickly" };
    }));
    Files.Write(Path.Combine(home,"RESULTS.json"),cases);
    Console.WriteLine(File.ReadAllText(Path.Combine(home,"RESULTS.json")));
    return 0;
  }
}
