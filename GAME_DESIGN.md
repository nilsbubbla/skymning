# Skymning – kurragömma

## Upplevelse och målgrupp
Ett svenskt, lättillgängligt spel från cirka 10 år. Korta, nerviga rundor där terrängen ger verkliga gömställen. Ovanifrånperspektiv, direkt rörelse och tydlig återkoppling. Prioritera en komplett liten spelupplevelse.

## Spelloop
Välj Skogsgläntan eller Gamla stan → välj träning eller lokal Wi-Fi → 20 sekunder att gömma sig → sökaren släpps fri i 100 sekunder → fånga genom att komma nära med fri sikt → resultat och ny runda. Gömda spelare vinner när tiden löper ut; sökaren vinner när alla hittats. Datorstyrda spelare fyller lediga platser. Träning låter spelaren välja roll. I Wi-Fi är värden sökare och gäster gömmare.

## Mekaniker
Solida hinder blockerar både rörelse och sikt. Sökaren ser gömmare endast inom synavstånd och med fri sikt. Gömmare kan spurta kort, men lämnar då synliga spår. Sökaren har en puls med nedkylning som visar ungefärliga riktningar, utan att avslöja exakta positioner. Utslagna spelare får följa sökaren. Joystick till vänster och en stor förmågeknapp till höger. Sökaren står stilla under gömfasen och kan inte se gömmarnas förflyttningar.

## Introduktion och progression
En inbyggd regelvy och korta instruktioner för aktuell roll och fas. Båda kartorna öppna från början för rättvisa matcher. Lokala rundor och segrar sparas; erfarenhet ger kosmetiska rangnamn, inga spelfördelar. Ingen reklam, inga köp, inga konton.

## Visuell identitet och ljud
Namnet Skymning, mörkt blågrön UI, varm elfenbenstext, mint och aprikos som rollfärger. Egen vektorritad skog och stad, mjuka skuggor, diskreta partiklar, pulser och fångstanimationer. Alla resurser skapas i projektet. Korta syntetiserade ljud för fasbyte, förmåga och resultat; ljud och vibration kan stängas av.

## Teknik
Native Android Java, Canvas och Choreographer med fast simuleringstakt. Ren Java-spelkärna för deterministiska tester. Auktoritativ lokal TCP-värd på port 28741, versionskontroll och begränsade meddelanden. Gäster skickar bara kontrollinput. Tillstånd skickas från värden; inget internet eller externt backend krävs. Paus fryser ensamspel. I nätverk fortsätter matchen hos värden, värdens bakgrundsläge avslutar sessionen tydligt.

## Verifierbara acceptanskriterier
- Byggbar APK från dokumenterat kommando och inga externa resurslicenser att hantera.
- Båda kartorna har nåbara gömställen och hinder stoppar rörelse/sikt.
- Gömfas, sökfas, fångst, vinst, förlust och omstart fungerar.
- Förmågor respekterar nedkylning; bots kan förflytta sig runt hinder.
- Lokal multiplayer synkroniserar match och hanterar bortkoppling.
- Kontroller stöder samtidig rörelse och förmåga.
- Paus, appväxling, sparade inställningar och progression fungerar.
- Menyer och HUD är läsliga på flera bildförhållanden.
- Tester, installation och faktisk fysisk körning dokumenteras separat; saknad USB-auktorisering får aldrig räknas som godkänt telefontest.
