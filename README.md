# Skymning

[Ladda ner senaste Android-APK](https://github.com/nilsbubbla/skymning/releases/latest) · [GitHub-projekt](https://github.com/nilsbubbla/skymning)

Färdig lokal leverans: `delivery/Skymning-1.0.apk`. Installerad och regressionstestad på Pixel 7 Pro. Kontrollsumma och testbevis finns i samma mapp. Begränsningar redovisas i `TEST_REPORT.md`.

Ett svenskt Androidspel om kurragömma för 1–6 spelare. Göm dig under 20 sekunder, överlev 100 sekunders jakt eller hitta alla fem gömmare. Spela offline med bots eller tillsammans på samma Wi-Fi.

## Bygg
Krav: JDK 17, Android SDK med plattform 36 och build-tools 36.0.0, `ANDROID_HOME` och `JAVA_HOME`. Gradle Wrapper 9.4.1 ingår. Första bygget på en ny dator behöver internet för Gradle och Android Gradle Plugin 9.2.1; själva spelet behöver ingen internetanslutning.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

APK: `app/build/outputs/apk/debug/app-debug.apk` respektive `app/build/outputs/apk/release/app-release.apk`. Release är signerad med datorns Android-utvecklingsnyckel för lokal installation. För butikspublicering behövs en egen skyddad signeringsnyckel. Denna leverans gör ingen publicering.

Projektmappen innehåller `ö`, därför används Androids `overridePathCheck`. Bygget är verifierat i den här Windows-mappen. På en ny dator kan en ren ASCII-sökväg användas om SDK-verktyg skulle ha problem.

## Installera och testa
Anslut telefonen, aktivera USB-felsökning och godkänn datorn på telefonen.

```powershell
adb devices -l
.\scripts\Test-Core.ps1
.\scripts\Test.ps1 -FullRound
```

Testflödet bygger, installerar, startar, styr, kontrollerar paus/appväxling och samlar skärmbilder, UI-träd, logcat, minnes- och bildrutestatistik i `test-output`. Det ändrar inte telefonens systeminställningar. En obehörig/avstängd enhet ger ett uttryckligt blockeringsresultat.

`scripts/Install.ps1` har två minuters tidsgräns och hanterar den specifika svenska Play Protect-frågan om frivillig uppladdning av Skymning genom att välja ”Skicka inte”. Skyddsinställningar ändras inte. Andra säkerhetsdialoger måste bedömas separat. Slutlig APK kan regressionstestas med `scripts/Test.ps1 -SkipBuild -FullRound -ApkPath delivery/Skymning-1.0.apk`.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w se.nils.skymning.test/se.nils.skymning.DeviceRegression
```

Instrumentering kompletterar ADB med samtidig multitouch och kontrollerade tillståndstester. Se `TEST_REPORT.md` för faktiskt körda tester och begränsningar.

## Spela tillsammans
1. Telefonerna ansluts till samma lokala nätverk.
2. Värden väljer karta, **Spela med vänner → Skapa rum**.
3. Övriga väljer **Anslut till vän** och anger värdens IPv4-adress.
4. Värden startar. Värden är sökare, gästerna gömmare. Lediga platser fylls av bots.

TCP-port 28741 måste vara nåbar. Gästnät och klientisolering kan förhindra anslutning. Ingen portvidarebefordran till internet behövs. En gäst som lämnar ersätts av en bot. Om värden lämnar appen stängs rummet; gästerna får ett meddelande. En pauspanel i nätverk stoppar bara egna kontroller, inte matchen.

## Struktur
- `core/src`: Android-oberoende regler, kartor, AI och LAN-transport.
- `app/src/main`: Canvasgränssnitt, touch, ljud, Androidlivscykel och sparning.
- `tests`: reproducerbara Java-tester och desktopmotpart för nätverksintegration.
- `scripts`: PowerShell-flöden.
- `delivery`: slutlig APK, kontrollsumma och leveransinformation.

All grafik ritas av projektets kod. Ljudeffekterna syntetiseras matematiskt. Systemtypsnitt används. Inga hämtade bild- eller ljudresurser, annonser eller analys-SDK:er ingår.
