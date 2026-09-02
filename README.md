Šī GitHub repozitorija satur bakalaura darba laikā izstrādātās ceļa seguma kvalitātes novērtēšanas sistēmas komponentes, kas netika ievietotas paša darba PDF faila pielikumu sadaļā. Repozitorijas nosaukums "CKNS" lietots kā saīsinājums "Ceļu Kvalitātes Novērtēšanas Sistēma". Repozitorijas "main branch" atrodas visi, zemāk minētie, faili; savukārt "master branch" satur tikai lietotnes pirmkodu (Android Studio projektu eksportēšanas īpatnību dēļ tika veidots atsevišķs branch). Abu "branch" android leitotnes pirmkodi ir vieni un tie paši.

Repozitorija satur:  
```      Kompilēto Android lietotni (ar "400px" ONNX modeli, jo tas testos uzrādīja vislielāko precizitāti);```    
```      Pilno Android lietotnes kodu, kas ticis eksportēts no "Android Studio";```    
```      Trīs darba pamattekstā minētos .pth "oriģinālā" modeļa failus;```    
```      Trīs darba pamattekstā minētos konvertētos ONNX modeļa failus.```    


Autors norāda, ka nav iekļauti MBTile tipa kartes faili - tos potenciālajam lietotājam pašam jāatrod, ja ir vēlme izmantot lietotnes bezsaistes kartes funkcionalitāti. Tostarp, no Android lietotnes pirmkoda ir izņemts Google Maps API key - tas arī, potenciālajam lietotājam, pašam jānorāda (AndroidManifest.xml fails), ja ir vēlme paša spēkiem kompilēt lietotni.

Android lietotnes pirmkoda kompilācijai, ja tiek mainīts ONNX modelis (pēc noklusējuma izmantots "400px" apakšmodelis) nepieciešams manuāli nomainīt ievades izmēru MainActivity.kt failā (koda pašā apakšā konstantes "TARGET_W" un "TARGET_H") uz vainu 550 (priekš 550px apakšmodeļa), vai 720 (priekš 720px apakšmodelā). Aplikācijā izmantotais ONNX modelis un klašu fails (class_names.json) glabāts sekojošā failu direktorijā "\app\src\main\assets". Noklusējuma koordinātas, MBTiles kartes režīma pozicionēšanai iestatītās uz Rīgu (lat. 56.9475; lon. 24.1063), taču ja tiek lietota MBTiles karte ar citu reģionu, nepieciešams manuāli šīs koordinātas atjaunot (Mbtiles.kt), lai nodrošinātu precizitāti.

