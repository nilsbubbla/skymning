# Testning

Kör från projektmappen i PowerShell:

```powershell
.\scripts\Test-Core.ps1
.\scripts\Test.ps1 -FullRound
```

`Test-Core.ps1` kompilerar den rena Java-kärnan och kör `GameTest` samt `LanTest` med assertions aktiverade. LAN-testet använder riktiga loopback-sockets och tar minst cirka 13 sekunder eftersom en inaktiv anslutning prövas förbi lästimeouten. Ingen telefon behövs.

`Test.ps1` kör kärntester, bygger, installerar och startar Androidappen. Därefter kontrollerar det processen och aktiviteten, trycker på träningsknappen, provar rörelse/förmåga, paus, bakgrund/återupptagning och upprepade tryck. Med `-FullRound` väntar det även på rundresultatet. APK kan väljas med `-ApkPath`; `-SkipBuild` återanvänder redan byggd APK. Vid flera telefoner krävs `-Serial <id>`. En emulator måste väljas uttryckligen med `-AllowEmulator` eller `-Serial`.

Tryck anges i spelets virtuella koordinatsystem 1280×720, inte som procent av telefonens skärm. Skriptet använder vyns exakta transformation (`uiX`, `uiY`, `uiScale`) och UI-trädets vynuläge när dessa markörer finns. Annars beräknas transformationen från vyns mått och skärmens kamerautskärning. Koordinaterna dokumenteras i `touch-transform.txt`. Träningsknappen kan justeras med `-TrainingX` och `-TrainingY` (standard 285, 332).

Rapport, skärmbilder, UI-träd, logcat, minnes- och renderingsdiagnostik sparas under `test-output/device-<tid>/`. `report.json` skiljer `BLOCKED`, `FAIL`, `SMOKE_PASS` och partiellt verifierad körning. USB-status `unauthorized` är blockerad testning, aldrig godkänd. Skriptet varken låser upp telefonen eller ändrar dess säkerhetsinställningar.

## Begränsningar och manuell kontroll

- Ett godkänt smoke-test bevisar inte subjektiv spelkänsla eller samtidig multitouch. Prova joystick och förmåga samtidigt på fysisk telefon.
- Granska skärmbilderna: läsbar text, inga överlapp, korrekt skalning och synliga återkopplingar.
- Prova båda kartorna, båda rollerna, vinst och förlust, omstart och sparad progression.
- Testa två fysiska telefoner på samma Wi-Fi för faktisk nätverkslatens, avbrott och routerisolering. Loopback-test verifierar transporten, inte telefonernas Wi-Fi-miljö.
- `dumpsys gfxinfo` fångar Androids renderingsstatistik men är inte en komplett prestandaprofil. `meminfo` är en ögonblicksbild och bevisar inte frånvaro av minnesläckor.
- Skärmmarkörerna i Androidvyn (`screen=menu`, `screen=game`, `screen=result`) låter UI Automator verifiera flödet. Saknade markörer redovisas som ej verifierade.
