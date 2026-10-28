# Skymning 1.0

`Skymning-1.0.apk` är slutlig release för lokal installation, 676 502 byte. Den är redan installerad på Pixel 7 Pro och byteidentisk med filen här. SHA-256 finns i SHA256SUMS.txt. Signering: Android utvecklingsnyckel, APK v2; ingen butikspublicering.

## Innehåll
- Skogsgläntan och Gamla stan, ovanifrånperspektiv.
- Gömfas, tidsbegränsad jakt, fångst, resultat och omspel.
- Offline-träning som gömmare eller sökare med fem bots.
- Lokal Wi-Fi för upp till sex spelare, bots fyller lediga platser.
- Styrspak, samtidig spurt/puls, siktblockerande hinder, spår och fångsteffekter.
- Regler, paus, inställningar, sparade segrar/erfarenhet och kosmetiska ranger.
- Egen grafik, syntetiserade ljud, vibration och Android-livscykelhantering.

## Testat
737 kärnassertions, 26 LAN-kontroller, 83 Androidassertions och slutlig fysisk regression med 22 kontrollpunkter passerade. Direkt telefon–dator-Wi-Fi testat i båda rollerna, inklusive full runda, omspel, input och bortkoppling. Release återställde match efter processavslut i bakgrunden. Ingen krasch eller ANR i sista körningen.

`verification` innehåller rårapporter och skärmbilder. Full testbeskrivning och verifieringsgränser: projektets TEST_REPORT.md. Bygginstruktioner: README.md. Git innehåller stabila milstolpar och slutleverans.

## Begränsningar
Ingen andra fysisk Androidtelefon fanns tillgänglig. Telefon–telefon-match, äldre enheter och mänsklig långtidsspeltestning är därför inte verifierade. Nätverket är avsett för samma lokala Wi-Fi; ingen internetmatchning eller kontotjänst ingår. Canvasgränssnittet har inte komplett TalkBack-stöd.

## Installera på ytterligare telefon
Kopiera APK:n till telefonen och öppna den, eller anslut via USB och kör `adb -s SERIAL install -r delivery/Skymning-1.0.apk` från projektmappen. Godkänn installationen av din egen app när Android begär det. Ändra inte globala säkerhetsinställningar för testning.
