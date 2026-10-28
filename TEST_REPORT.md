# Testprotokoll – Skymning 1.0

28 oktober 2026. Resultaten avser utförda tester. Syntetiska testtillstånd är särskilt markerade.

## Miljö
Windows, Java 17.0.19, Gradle 9.4.1, Android Gradle Plugin 9.2.1, SDK 36. Fysisk Pixel 7 Pro, Android 16, arm64-v8a, 1080×2340. ADB 32201FDH3000M5 var inledningsvis unauthorized; användaren godkände USB-felsökning. Ingen emulator eller andra fysisk telefon användes.

## Automatiska resultat
| Test | Resultat | Evidens |
|---|---|---|
| Ren release-ombyggnad | Godkänd | `gradlew clean assembleRelease` |
| APK-signatur | Godkänd, v2, en signerare | Android apksigner verify |
| Spelkärna | 737 assertions godkända | delivery/verification/core-tests.txt |
| LAN med riktiga loopback-sockets | 26 kontroller godkända | Samma rapport |
| Androidinstrumentering | 7 fall, 83 assertions, 0 fel | delivery/verification/instrumentation.txt |
| Android lint | 0 fel, 6 varningar | delivery/verification/lint.txt |
| Första fysisk helrunda | SMOKE_PASS | test-output/device-20261028-203045/report.json |
| Slutlig release-regression | SMOKE_PASS, 22 kontrollpunkter | delivery/verification/device-release.json |

Kärntesterna täcker faser, vinst/förlust, lokal fångad spelares förlust trots överlevande lagkamrater, kollisioner, sikt, spurtens risk/nedkylning, puls, diagonal input, gränser och ogiltig input. BFS verifierar att samtliga gångbara rutnätsceller på båda kartorna är sammanhängande. Snapshot-tester provar trunkering, fel version och NaN/±Infinity i samtliga flyttalsfält utan partiell tillståndsändring.

16 botmatcher fullföljdes utan att fastna. Skogen: medeltid 80,7 sekunder inklusive gömfas, sökaren vann 8/8. Staden: 86,2 sekunder, sökaren vann 7/8. Detta mäter botbalans, inte människors svårighetsupplevelse. Desktopbenchmark cirka 95 ms för 10 000 tick är inte telefonens bildfrekvens.

LAN-tester täcker spelar-ID, tvåvägstrafik, riktad sändning, full lobby, återanslutning, avbrott, protokollversion, ogiltiga ramlängder och heartbeat efter 12,5 sekunders inaktivitet.

## Fysisk telefon
Slutlig körning 20:50–20:52 på leverans-APK:n passerade hela flödet inklusive processåterställning (PID ändrades från 26262 till 26684, matchen åter i pausat läge) och helrunda till resultat. Ingen krasch eller ANR i slutloggen. Installerad base.apk hämtades och hade samma SHA-256 som leveransfilen: `0e18780b114395b88670d76b05002801f95378c55ac464a8a87712e01ed84efe`.

Efter slutrundan verifierades Androids bakåtknapp från regler till meny och sparad ljudinställning över kallstart. Ljud återställdes till på. Svepgestens beteende är inte separat godkänt i telefonens nuvarande navigeringsläge.

Frame-statistik efter processåterställningen: 8 589 rutor på cirka 143 sekunder, ungefär 60 rutor/s i genomsnitt. Android rapporterade 4 deadline-missar/0,05 % janky frames. Det äldre legacy-måttet rapporterade samtidigt 86,48 % och 95:e percentilen för rapporterad frame duration var 24 ms. Mätetalen är inte identiska och ska inte tolkas som garanterad 16,7 ms end-to-end-latens. GPU:s 95:e percentil var 3 ms. TOTAL PSS cirka 103 MiB. Rådata i delivery/verification/frames.txt och memory.txt.

ADB-smoke styrde meny, gömmarträning, joystick, förmåga, paus, Fortsätt, Home/appväxling, återgång och snabba upprepade tryck. En full runda nådde resultat. Ingen paketrelaterad krasch eller ANR hittades i den godkända körningens logcat.

Instrumentering skickade riktiga MotionEvent med två oberoende pointer-ID:n. Rörelse samtidigt med spurt, fortsatt rörelse efter förmågefingrets släpp och stopp efter joysticksläpp verifierades. Paus frös tiden. Stad och sökarroll valdes via menytryck; sökaren kunde inte röra sig eller se gömmare under gömfasen.

Resultat- och belöningstester använde uttryckligen **kontrollerade fixtures** med spelare nära varandra eller förkortad tid. De ersätter inte helrundetestet. De verifierade fångst, vinst/förlust, en belöning och omspel. Sparade räknare återställdes efteråt.

Offscreen-rendering på Android skapade 18 bilder: meny, regler, inställningar, Wi-Fi, skog och stad vid 1280×720, 1600×720 och 1024×768. Alla knapprektanglar låg inom designytan. Meny i 4:3, regler i 16:9 och inställningar på bred skärm granskades visuellt, liksom fysiska skärmbilder av meny, båda kartorna och paus. Ingen observerad textkapning eller blockerande överlappning.

## Verklig Wi-Fi-integration
Direkt befintligt Wi-Fi mellan telefon 192.168.50.8 och dator 192.168.50.233. Inga brandväggs-/nätverksinställningar ändrades; ingen ADB-tunnel användes.

- Android som värd: gömfas → sökfas → resultat → omspel. Desktopgästen mottog 1 727 snapshots och skickade 1 627 inputpaket. Rörelse och förmåga verifierades i värdens tillstånd. Helrunda i riktig tid.
- Android som gäst: två rundor, därefter värden stängd och klient tillbaka i menyn. Desktoptestvärden körde 6× tid. Sparning ökade exakt två rundor och 70 XP trots upprepade resultatpaket.
- Gästinput separat vid 2× tid: 604 inputpaket, varav 32 rörelsepaket och ett förmågepaket från ADB-tryck. Stadens rörelse och nedkylning dokumenterades med skärmbild.

Rårapporter finns i delivery/verification/android-host-wifi.txt, android-guest-wifi.txt och android-guest-input.txt. Accelererad tid finns bara i desktoptestverktyget.

## Åtgärdade fel
- Upprepade resultatpaket gav upprepad klientbelöning. Rättad rundövergång; exakt progression verifierad över två nätverksrundor.
- Väntande anslutning kunde slutföras i bakgrunden. Generationstoken avbryter nu även detta fall.
- Ryckig gästgrafik: renderinterpolering tillagd utan att ändra auktoritativa regler.
- Android 16-bakåtgest: native dispatcher tillagd, äldre fallback kvar.
- En mellanversion kraschade då InsetsController hämtades före DecorView skapats. Initialisering flyttad efter setContentView; kallstart och slutlig instrumentering godkända.
- Två stilattribut för nya för minSdk 26 flyttades till versionsskyddad kod eller togs bort.
- Testskriptets `adb -W` tolkades som PowerShell-parameter. Argumenthantering rättad och återtestad.
- Frivillig Play Protect-uppladdning kunde blockera installation. Install.ps1 hanterar känd dialog utan uppladdning eller ändrade skyddsinställningar och har tidsgräns.

## Kvarstående verifieringsgränser
- En fysisk Androidmodell. Ingen telefon–telefon-match eller sex samtidiga fysiska enheter har provats. Sexplatstaket är testat med desktopklienter.
- Äldre Androidversioner och surfplattor är inte fysiskt testade. API-kompatibilitet och bildförhållanden har kontrollerats separat.
- Ingen mänsklig långtidsspeltestning. Upplevd svårighetsgrad, högtalarljud och vibrationens subjektiva kvalitet är inte verifierade av en människa.
- Ingen batteri-/minnesprofilering över många timmar; korta mätningar bevisar inte frånvaro av alla läckor.
- Canvasgränssnittet saknar komplett TalkBack-navigation.
- APK är för lokal installation med Androids utvecklingsnyckel. Butikspublicering och produktionssignering ingår inte.
- Kvarvarande lintvarningar gäller nyare SDK/Gradle, landskapsorientering på stora skärmar, backupdeklaration och XML-konstruktor för programmässig vy. Inga lintfel.
