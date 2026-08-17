# Fiskal HTML v2

Android aplikacija za srpske fiskalne račune.

## Funkcije

- skeniranje QR koda sa fiskalnog računa;
- automatsko preuzimanje JSON podataka sa PFR URL-a;
- automatsko popunjavanje prodavca, PIB-a, lokacije, PFR broja, vremena, iznosa i stavki;
- prikaz statusa validnosti računa;
- otvaranje zvanične PFR provere;
- izvoz svakog računa u zaseban HTML fajl.

Aplikacija prihvata samo HTTPS PFR URL-ove sa `suf.purs.gov.rs` i poddomena.

APK se gradi GitHub Actions workflow-om sa grane `fiskalhtml-v2`.
