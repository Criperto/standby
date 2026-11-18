# Reemplazar el icono de la app con tu archivo `.svg`

1. Exportá o guardá tu diseño `.svg` como recurso vectorial de Android (`ic_launcher_foreground.xml` y opcionalmente `ic_launcher_monochrome.xml` para íconos temáticos de Material You) dentro de:
   - `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
   - `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`
2. Para convertir un `.svg` directamente sin abrir Android Studio podés usar `svg2android` o el plugin Vector Asset, asegurándote de mantener el lienzo en `108dp × 108dp` con el arte centrado dentro de la zona segura de `66dp × 66dp`.
3. Si querés cambiar el color de fondo del icono adaptativo, editá:
   - `android/app/src/main/res/values/colors.xml` (`ic_launcher_background`).
4. Volvé a compilar el APK con `./scripts/build-apk.sh`.
