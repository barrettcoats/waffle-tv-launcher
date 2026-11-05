# Third-party licenses

The launcher's own code is MIT-licensed (see `../LICENSE`). It bundles:

- **Inter** font (`app/src/main/res/font/inter_*.ttf`), © The Inter Project Authors,
  SIL Open Font License 1.1: `Inter-OFL.txt`.

Library dependencies are pulled in by Gradle, not vendored:

- AndroidX, Jetpack Compose, kotlinx.serialization: Apache-2.0
- NanoHTTPD 2.3.1 (the phone photo-upload page): BSD-3-Clause
- ZXing core 3.5.3 (the QR code): Apache-2.0

Weather data: Open-Meteo.com, CC BY 4.0. Credited on the forecast screen and in About.
