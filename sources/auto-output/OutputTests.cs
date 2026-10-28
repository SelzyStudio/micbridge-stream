using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ApolloMic.Audio;

namespace MicBridge.AutoOutput {
  public sealed class FakeOutput : IOutputAudio {
    public string[] Roles = { "speaker", "speaker", "speaker" };
    public readonly Dictionary<string,string> Available = new Dictionary<string,string> {
      { "speaker", "Desktop speakers" }, { "headset", "USB headset" }, { "communications", "Communications headset" },
      { "steam", "Speakers (Steam Streaming Speakers)" }, { "mic-render", "Speakers (Steam Streaming Microphone)" }
    };
    public readonly List<DefaultEndpoint> Writes = new List<DefaultEndpoint>();
    public bool FailChange;
    public DefaultEndpoint[] Defaults() {
      return Enumerable.Range(0,3).Select(i => new DefaultEndpoint { flowIndex=0,roleIndex=i,flow="Render",role=i.ToString(),
        deviceId=Roles[i],name=Available.ContainsKey(Roles[i])?Available[Roles[i]]:Roles[i] }).ToArray();
    }
    public Endpoint[] Endpoints() {
      return Available.Select(x => new Endpoint { flowIndex=0,flow="Render",state=1,deviceId=x.Key,name=x.Value }).Concat(new [] {
        new Endpoint { flowIndex=1,flow="Capture",state=1,deviceId="capture-steam",name="Steam Streaming Microphone" }
      }).ToArray();
    }
    public void Change(DefaultEndpoint[] rows) {
      if (FailChange) throw new IOException("Simulated output device failure");
      foreach(var row in rows) {
        if (row.flowIndex != 0) throw new Exception("Capture mutation attempted");
        if (!Available.ContainsKey(row.deviceId)) throw new IOException("Unavailable output");
        Roles[row.roleIndex]=row.deviceId; Writes.Add(row);
      }
    }
  }
  public static class OutputTests {
    static readonly List<string> passed = new List<string>();
    static void Check(bool condition,string name) { if(!condition)throw new Exception(name);passed.Add(name); }
    static string Folder(string root,string name) { string path=Path.Combine(root,name);Directory.CreateDirectory(path);return path; }
    static OutputRouting Policy(FakeOutput audio,string folder) { return new OutputRouting(audio,folder,delegate{}); }
    static bool All(FakeOutput audio,string id) { return audio.Roles.All(x=>x==id); }
    public static int Run(string reportPath) {
      string root=Path.Combine(Path.GetTempPath(),"MicBridgeOutputTests-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);passed.Clear();
      try {
        string folder=Folder(root,"normal");var audio=new FakeOutput();var policy=Policy(audio,folder);
        policy.Tick(false,false,0);Check(policy.BaselineReady&&audio.Writes.Count==0,"idle baseline never changes output");
        audio.Roles=new[]{"steam","steam","steam"};policy.Tick(false,false,100);
        policy.Tick(true,false,250);Check(policy.Applied&&File.Exists(Path.Combine(folder,"original-output.json")),"Steam selected before active cannot replace physical baseline");
        policy.Tick(false,false,500);Check(All(audio,"steam"),"short disconnect grace retains Steam");
        policy.Tick(true,false,1000);Check(policy.Applied&&All(audio,"steam"),"reconnect grace retains original journal");
        policy.Tick(false,true,3500);Check(All(audio,"speaker")&&policy.RestorationPending,"disconnect restores original outputs before guard completion");
        policy.Tick(false,true,5501);Check(!policy.RestorationPending,"same-role restore completes after stable two seconds");
        Check(audio.Writes.All(x=>x.flowIndex==0),"all mutations are render-only");

        folder=Folder(root,"distinct");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);policy.Tick(false,true,2500);
        Check(audio.Roles.SequenceEqual(new[]{"speaker","headset","communications"})&&policy.RestorationPending,"three original output roles restored individually");
        audio.Roles=new[]{"speaker","speaker","speaker"};policy.Tick(false,true,30000);
        Check(audio.Roles.SequenceEqual(new[]{"speaker","headset","communications"}),"late Apollo Console-to-all restoration is corrected");
        policy.Tick(false,true,37501);Check(!policy.RestorationPending,"role-distinct guard ends after thirty-five seconds");

        folder=Folder(root,"manual");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);policy.Tick(false,true,2500);audio.Roles[1]="communications";
        policy.Tick(false,true,3000);Check(audio.Roles[1]=="communications","arbitrary manual output choice during guard is preserved");
        audio.Roles=new[]{"speaker","speaker","speaker"};policy.Tick(false,true,3200);
        Check(audio.Roles[1]=="communications","manual preference survives a later known Apollo restoration");

        folder=Folder(root,"partial-first-restore");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);audio.Roles=new[]{"speaker","speaker","steam"};policy.Tick(false,true,2500);
        audio.Roles=new[]{"speaker","speaker","speaker"};policy.Tick(false,true,3000);policy.Tick(false,true,37501);
        Check(audio.Roles.SequenceEqual(new[]{"speaker","headset","communications"})&&!policy.RestorationPending,"partial Apollo cleanup before first restore cannot overwrite a role original");

        folder=Folder(root,"partial-late-restore");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);policy.Tick(false,true,2500);audio.Roles=new[]{"speaker","speaker","communications"};policy.Tick(false,true,3000);
        Check(audio.Roles.SequenceEqual(new[]{"speaker","headset","communications"}),"late sequential Apollo cleanup is recognized per role");

        folder=Folder(root,"partial-mixed-manual");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);policy.Tick(false,true,2500);audio.Roles=new[]{"headset","speaker","communications"};policy.Tick(false,true,3000);
        Check(audio.Roles.SequenceEqual(new[]{"headset","headset","communications"}),"partial Apollo cleanup and an independent manual role choice coexist safely");

        folder=Folder(root,"baseline-write-failure");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);audio.Roles=new[]{"headset","headset","headset"};
        policy.Tick(false,true,2500);string baselineTemp=Path.Combine(folder,"output-baseline.json.tmp");Directory.CreateDirectory(baselineTemp);policy.Tick(false,true,4501);
        Check(policy.Error!=null&&policy.RestorationPending&&File.Exists(Path.Combine(folder,"original-output.json")),"failed final baseline persistence retains the manual-choice recovery journal");
        Directory.Delete(baselineTemp);policy=Policy(audio,folder);audio.Roles=new[]{"steam","steam","steam"};policy.Tick(false,false,0);
        Check(All(audio,"headset")&&policy.RestoreNow(),"restart after baseline commit failure restores the preserved manual choice");

        folder=Folder(root,"manual-console-reconnect");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);
        policy.Tick(false,false,0);policy.Tick(true,false,250);policy.Tick(false,true,2500);audio.Roles[0]="communications";policy.Tick(false,true,3000);
        policy.Tick(true,false,3250);policy.Tick(false,true,5500);audio.Roles=new[]{"communications","communications","communications"};policy.Tick(false,true,30000);
        Check(audio.Roles.SequenceEqual(new[]{"communications","headset","communications"}),"new logical connection rebases Apollo Console signature to preserved manual choice");

        folder=Folder(root,"missing");audio=new FakeOutput();audio.Available.Remove("steam");policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        Check(!policy.Applied&&policy.Error!=null&&audio.Writes.Count==0,"missing Steam Speakers does not choose render microphone");
        audio.Available["steam"]="Steam Streaming Speakers";audio.Available["steam2"]="Steam Streaming Speakers";policy.Tick(true,false,500);
        Check(!policy.Applied&&audio.Writes.Count==0,"ambiguous Steam Speakers makes no output changes");
        audio.Available.Remove("steam2");policy.Tick(true,false,750);Check(policy.Applied&&All(audio,"steam"),"available unique Steam target recovers without reconnect");

        folder=Folder(root,"active-start");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(true,false,0);
        Check(!policy.Applied&&policy.Error!=null&&audio.Writes.Count==0,"startup while active without baseline fails safely");
        policy.Tick(false,false,500);policy.Tick(true,false,750);Check(policy.Applied,"idle baseline enables a subsequent connection");

        folder=Folder(root,"hotplug");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        audio.Available.Remove("steam");audio.Roles=new[]{"headset","headset","headset"};policy.Tick(true,false,500);
        Check(!policy.Applied&&All(audio,"headset"),"missing target does not fight Windows fallback");
        audio.Available["steam"]="Steam Streaming Speakers";policy.Tick(true,false,750);Check(policy.Applied&&All(audio,"steam"),"target reappearance reapplies Steam once");
        audio.Roles=new[]{"headset","headset","headset"};int writes=audio.Writes.Count;policy.Tick(true,false,1000);
        Check(All(audio,"headset")&&audio.Writes.Count==writes,"normal manual choice during active session is not continuously fought");
        policy.Tick(false,true,3250);Check(All(audio,"headset"),"arbitrary active manual choice is retained on disconnect");

        folder=Folder(root,"original-unplugged");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        audio.Available.Remove("speaker");policy.Tick(false,true,2500);
        Check(policy.RestorationPending&&All(audio,"steam"),"missing original retains restoration journal");
        audio.Available["speaker"]="Desktop speakers";policy.Tick(false,true,5000);Check(All(audio,"speaker"),"original reappearance completes device restoration");
        policy.Tick(true,false,5250);policy.Tick(false,true,7500);Check(All(audio,"speaker"),"reconnect while restoration pending retains the original baseline");

        folder=Folder(root,"manual-missing-original");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);audio.Available.Remove("speaker");
        policy.Tick(false,true,2500);audio.Roles=new[]{"headset","headset","headset"};policy.Tick(false,true,3000);audio.Available["speaker"]="Desktop speakers";policy.Tick(false,true,3500);
        Check(All(audio,"headset"),"manual choice while original is unavailable is preserved after original returns");

        folder=Folder(root,"manual-after-fallback");audio=new FakeOutput();audio.Roles=new[]{"speaker","headset","communications"};policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        audio.Available.Remove("steam");audio.Roles=new[]{"headset","headset","headset"};policy.Tick(false,true,2500);audio.Roles[0]="communications";policy.Tick(false,true,3000);
        Check(audio.Roles[0]=="communications","manual choice after fallback normalization is not fought during guard");

        folder=Folder(root,"restart");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        policy=Policy(audio,folder);policy.Tick(false,false,0);Check(All(audio,"speaker")&&policy.RestorationPending,"journal restart restores even without a new disconnect marker");
        Check(policy.RestoreNow()&&!policy.RestorationPending,"explicit restore completes immediately without thirty-five-second wait");

        folder=Folder(root,"fallback");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);policy.Tick(true,false,250);
        audio.Available.Remove("steam");audio.Roles=new[]{"headset","headset","headset"};policy.Tick(false,true,2500);
        Check(All(audio,"speaker"),"target disappearance fallback is restored rather than misread as a manual choice");

        folder=Folder(root,"failed-change");audio=new FakeOutput();policy=Policy(audio,folder);policy.Tick(false,false,0);audio.FailChange=true;policy.Tick(true,false,250);
        Check(policy.Error!=null&&File.Exists(Path.Combine(folder,"original-output.json"))&&All(audio,"speaker"),"failed target change retains originals before mutation");
        audio.FailChange=false;policy.Tick(true,false,500);Check(policy.Applied,"temporary device failure retries safely");

        folder=Folder(root,"corrupt");audio=new FakeOutput();File.WriteAllText(Path.Combine(folder,"original-output.json"),"{}");policy=Policy(audio,folder);policy.Tick(true,false,0);
        Check(policy.Error!=null&&audio.Writes.Count==0&&!policy.RestoreNow(),"corrupt recovery record fails closed without audio mutations");
        folder=Folder(root,"invalid-flow");audio=new FakeOutput();Files.Write(Path.Combine(folder,"output-baseline.json"),new OutputBaseline{Defaults=new[]{new DefaultEndpoint{flowIndex=1,roleIndex=0,deviceId="capture"},new DefaultEndpoint{flowIndex=0,roleIndex=1,deviceId="speaker"},new DefaultEndpoint{flowIndex=0,roleIndex=2,deviceId="speaker"}}});
        policy=Policy(audio,folder);policy.Tick(true,false,0);Check(policy.Error!=null&&audio.Writes.Count==0,"capture endpoint injected into baseline is rejected");

        Files.Write(reportPath,new{success=true,passed=passed.Count,tests=passed.ToArray()});return 0;
      } catch(Exception e) {Files.Write(reportPath,new{success=false,passed=passed.Count,error=e.ToString()});return 1;}
      finally {try{Directory.Delete(root,true);}catch{}}
    }
  }
}
