# 自転車ルートロガー（Cycle Route Logger）

Android 用の自転車のルートと走行の記録アプリのソースコードです。
GPX のルートと経由地を Google マップ・航空写真に出して走り、走った道を GPS で記録します。

- 説明・APK のダウンロード: <https://kijitora-no-hito.github.io/android-apps/cycle_route_logger/>
- 設計: [docs/DESIGN.md](docs/DESIGN.md)

## ビルド

- Android Studio（JDK 17 以上）。`gradlew.bat test assembleDebug`（Windows）または `./gradlew test assembleDebug`。
- **Google Maps の API キーは自分で用意してください。** プロジェクトのルートの `local.properties` に
  `MAPS_API_KEY=...` と書きます（git 管理外）。無ければ地図は表示されません（`local.defaults.properties` のダミーが使われます）。
  キーには「Android アプリ」の制限（パッケージ名＋自分の署名証明書の SHA-1）と「Maps SDK for Android」だけの API 制限を掛けてください。
- release の署名は、ルートの `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）と鍵ファイルがあるときだけ行います（どちらも git 管理外）。
  無ければ release は署名なしでビルドされます。
- 自分で改造して配るときは、`applicationId`（`jp.kjtr.cycleroutelogger`）と、更新の確認の取得先（`update/`）を自分のものに変えてください。

## 第三者のサービス・データ

地図は Google マップ（Google の利用規約）、場所名の検索は OpenStreetMap の Nominatim（© OpenStreetMap contributors、ODbL。利用ポリシーに従い、検索ボタンを押したときだけ・1 秒に 1 回まで）を使います。
使っているライブラリはそれぞれのライセンスに従います（`gradle/libs.versions.toml`）。

## ライセンス

[MIT License](LICENSE)
