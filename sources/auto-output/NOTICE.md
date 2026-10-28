# MicBridge Auto Output notices

Copyright (C) 2026 Selzy Studio. This helper, its C# sources, tests and distribution scripts are provided under GNU GPL version 3; see LICENSE. No warranty is provided. The corresponding source is included with this add-on and in the repository.

CoreAudio.cs is the C# COM interop adapter prepared for this project. The Windows SDK declarations were checked against mmdeviceapi.h, propsys.h and endpointvolume.h. The undocumented IPolicyConfig method order and CLSID were checked against Apollo's src/platform/windows/PolicyConfig.h, which credits **EreTIk**, https://kitere.github.io/.

- Referenced header: https://github.com/SelzyStudio/micbridge-stream/blob/v1.0.0-rc1/sources/apollo/src/platform/windows/PolicyConfig.h
- Apollo / Sunshine notices remain in sources/apollo and the base release's corresponding source. This add-on does not bundle their executables or Steam drivers.
- Windows and Steam are trademarks of their respective owners. This is an unofficial community add-on, not a Microsoft, Valve, Apollo or Moonlight official release.

No external NuGet package or downloaded build dependency is used. Building requires the Windows x64 .NET Framework compiler; Windows runtime components are not redistributed here.
