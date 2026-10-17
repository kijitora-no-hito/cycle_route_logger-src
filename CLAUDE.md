# CLAUDE.md（cycle_route_logger）

自転車ルートロガー。Google マップ上で GPX ルート（ルートプラン）と経由地を見ながら走り、GPS の走行ログを記録する。
共通方針は `d:\work\android\CLAUDE.md`。設計の詳細は `docs/DESIGN.md`、配布・キー制限・鍵は `store/README.md`。

## 基本情報

- アプリ名: 自転車ルートロガー
- applicationId / namespace / パッケージ: `jp.kjtr.cycleroutelogger`（applicationId は公開後に変えられない）
- minSdk 24 / targetSdk 36 / compileSdk 36、versionCode 16 / versionName "0.11.0"（0.1.0 は 2026-10-11 作成・GitHub 公開済み。利用者の端末に実データあり。
  0.7.0 まで公開済み。0.8.0（2026-10-13、開拓したマスの地図・PhotoLocator の修正）・0.9.0（2026-10-15、測位の方式・軌跡のなめらかさ・獲得標高）・
  0.10.0（2026-10-16、ファイルから記録を取り込む）・0.10.1（2026-10-16、Strava の書き出し・activities.csv・活動単位の重複）・0.10.2（2026-10-17、まとめの地図をタイルに・バックアップと取り込みの取り違えの案内）・0.11.0（2026-10-17、記録の一覧の絞り込み・並べ替え）は dist にある）
- **記録の詳細**（0.5.0、DESIGN.md §4.5）: 地図はルートプランと同じ作り（`Settings.trackMapRatio`、全画面＋シート）。区間・分布は
  `stats/RideAnalysis`（保存した点を `RideTracker` に流し直し、`onCounted` で数えた分（`CountedStep`）を受け取る）。
  **`RideTracker` の数え方を変えるときは `onCounted` の呼び方も合わせる**（区間・分布の合計が走行時間・距離と一致することを `RideAnalysisTest` が見ている）。
- **全記録のまとめ**（0.7.0、DESIGN.md §4.7。drive_logger の「まとめ」の移植）: 記録の一覧の上のカード /［まとめ］→ `ui/SummaryScreen.kt`。
  計算は `stats/RideSummary.kt`（`RideSummaries`・`GridCells`。純ロジック）、材料は `data/SummaryRepository.kt`（記録ごとに点を `TrackRepository.analyze` で
  流し直し、統計の材料と間引いた線をメモリにキャッシュ）。時速の分布は記録の詳細と同じ `SpeedHistogramView`（2 km/h）。Room・通信・権限は変えていない。
- **まとめの地図はタイル**（0.10.2、DESIGN.md §4.7.1）: 記録が千本を超えると Polyline を足すだけで実機が OOM になったので、全走行の地図の軌跡と開拓したマスは
  `ui/SummaryTiles.kt` の `RideTileProvider`（TileOverlay。見えているタイルだけ Canvas で描く）、選んだ記録だけ Polyline、タップは `stats/MapTiles.nearest`。
  `RideLines` の線は緯度経度 × 1e6 の `IntArray`。**まとめの地図に記録の本数ぶん Polyline / Polygon を足す作りに戻さない**。
- **記録の一覧の絞り込み・並べ替え**（0.11.0、DESIGN.md §4.11）: 一覧の上の帯（`ui/TrackFilterUi.kt` の `TrackFilterBar`。［絞り込み（n）］・並べ替え・「1201 件中 37 件」・条件のチップの ×・［すべて解除］・虫眼鏡で名前の検索）、
  条件の画面 `TrackFilterScreen`（時期 = 年・月 / 期間・季節・曜日・時間帯（**開始時刻を端末のタイムゾーンで**）、場所、距離・平均・最高・走行時間・獲得標高（RangeSlider＋数値）、種類、並べ替え）、
  場所の画面 `ui/TrackPlaceScreen.kt`（円 = 地図の中心と半径 / 見えている範囲、通った / 出発 / 終了、場所名の検索は既存の Nominatim）。判定は純ロジック `stats/TrackFilter.kt`（`TrackFilters.apply`・`PlaceArea`・`TrackGeo`）、
  状態と保存は `data/TrackBrowser.kt`（SharedPreferences `track_filter` に JSON。**クラウドバックアップから除外**）。場所は **Room 版 7 の `track_geo`**（記録ごとの外接矩形・始点・終点・10 m で間引いた線。
  `data/TrackGeoIndex.kt` が起動時と記録の追加・変更時に無い・古い分だけバックグラウンドで作り、メモリにも持つ。**全点は持たない**）。絞り込んだまとめは `summary?filtered=true`（期間・フリー/ルートの切り替えは出さない・「初めて」の橙なし）。
  **場所の判定・索引の作り方を変えたら `TrackGeo.VERSION` を上げる**（古い行は作り直される）。
- **バックアップと取り込みの取り違え**（0.10.2、DESIGN.md §6.1）: `importer/FileKind`（zip の先頭から覗いて manifest.json → バックアップ、GPX/FIT/… や activities.csv → 記録のファイル）。
  設定の［バックアップを読み込む］は記録のファイルなら案内→同じ URI で `ActivityImporter.start`、取り込みでバックアップなら `State.LooksLikeBackup`。
- **開拓したマスの地図**（0.8.0、DESIGN.md §4.8。drive_logger 0.4.0 の移植）: `stats/CellShapes.kt`（マス → 矩形・同じ行の隣り合うマスをまとめる・
  表示範囲・初めて開拓したマス）と `ui/MapParts.kt` の `CellPolygons`。まとめの地図の［マス］（軌跡 / マス / 両方）・数字のタップ・期間で初めては橙、
  記録の詳細の［マス］（その記録で初めて走ったマス）。**マスの数え方（`GridCells`）を変えると「初めて」の判定も変わる**（`CellShapesTest`）。
- **測位の方式・軌跡のなめらかさ・獲得標高・比較表示**（0.9.0、DESIGN.md §4.9。drive_logger 0.8.0 の移植）: `sensor/LocationHub`（端末の GPS（既定）/ Google の融合測位。
  Play 開発者サービスが無い・失敗なら端末の GPS、`fusedActive`）、`geo/TrackSmoother`（カルマン＋RTS。`SmoothConfig.BICYCLE` = 加速度の雑音 1.2・外れ値 25 m/s）、
  `stats/ElevationGain`（獲得標高。`Live` は記録中の表示用）。**記録した点（DB）は変えない**。なめらかにした点は `TrackRepository.smoothed` / `displayPoints` で表示・計算のたびに作る
  （詳細・まとめ・SNS 画像・ルートとして保存・距離・区間・分布。GPX は書き出しで選ぶ）。終えた記録の統計（距離・獲得標高）は終えたときと、設定の切り替え・0.8.0 からの上書きの
  最初の起動で `recompute`（SharedPreferences `state` の `stats_points`）。**案内（外れ・進捗・到着）は測位のまま**（遅れを出さない）。
  **統計の数え方を変えたら `stats_points` の値を変えて既存の記録を計算し直させる**。
- **更新の確認**（0.4.0〜、Phonetic Hound と同じ仕組み。DESIGN.md §10）: 更新の確認のため GitHub（raw.githubusercontent.com）から公開の版一覧
  （android-apps の `releases.json`）を取得する。送るのは通常の通信の情報だけ。設定で OFF にできる（OFF の間は一切通信しない）。
  新しい版はホームの帯だけで知らせる（通知なし・記録中は出さない）。**公開のたびに android-apps の releases.json が更新されていること**が前提
  （`folder` = `cycle_route_logger`、タグ `cycleroutelogger-v<版>`、APK 名 `cycleroutelogger-<版>-release.apk` を変えない）。
  debug だけ `am start … --es updateAs 0.2.0` で古い版のふり。
- **場所名の検索**（0.6.0、DESIGN.md §4.6。trip_planner と同じ OSM Nominatim）: `geo/PlaceSearch.kt`（`PlaceSearch` が差し替え口・`NominatimSearch`・`RateGate`）と
  `ui/PlaceSearchUi.kt`（検索欄・結果の一覧・選んだ場所のカード）。ルートプラン・ルートを描く・走行画面に置いた。**検索ボタン / IME の検索キーのときだけ送る**
  （自動補完にしない）・前の検索から 1 秒あける・同じ検索はメモリのキャッシュから・User-Agent `CycleRouteLogger/<版> (jp.kjtr.cycleroutelogger)`。
  **結果を出す所には `NominatimSearch.ATTRIBUTION`（場所検索: Nominatim / © OpenStreetMap contributors）を必ず出す**（ODbL）。検索語と丸めた地図の範囲が
  OpenStreetMap Foundation に送られる（プライバシーポリシーに記載済み）。エミュレータの確認で Nominatim に負荷をかけない（数回だけ）。- **ファイルから記録を取り込む**（0.10.0、DESIGN.md §4.10）: 記録の一覧の右上 → SAF で zip / GPX / FIT / TCX / KML / KMZ / .gz → `importer/`（`ActivityReader` が zip・.gz を順に読み、`FitParser`（自前。Garmin SDK は使わない）・`TcxParser`・`KmlParser`・`GpxParser` の点を `RunCollector` が一時ファイル `FileSpool` へ、`DayPlanner` が重複（FIT > TCX > GPX > KML、前後 30 秒）を除いて端末のタイムゾーンの日付ごとに分け、`ImportBuilder` が `RideTracker` に通して 1 日 = 1 記録（free、`track.importFormat`、点の provider = 4））。流れと状態は `ActivityImporter`（`CycleApp.activityImport`、アプリのスコープ）、画面は `ui/TrackImportScreen.kt`。**`RideTracker` の数え方を変えたら取り込みの統計も変わる**（`ActivityImportTest`）。権限・通信は増えていない。
  0.10.1（DESIGN.md §4.10.1）: Strava の `activities.csv` を目録として読み（`ActivityCatalog`。種目はファイルより優先・名前を記録名に・Virtual Ride は既定で外す）、重複は**活動単位**（`DayPlanner.planAll`: 取り込みの印（Room 版 6 `import_source`）で黙って外す・既存の記録と軌跡を比べて同じ走行は外す・複数の機器の同じ走行は丸ごと 1 つ・別の場所で時間が重なれば同じ日の別の記録・前に取り込んだその日の記録には区間として足す）。確認画面は年・月で畳む。**実際の書き出しは利用者の走行なので git・dist・テストのリソースに入れない**（`ImportSampleTest` はパスがあるときだけ走る。確認のスクリーンショットは `captures/private/`）。
- edge-to-edge（targetSdk 36 で強制）。各画面は `WindowInsets.safeDrawing` / Scaffold で避ける。画面の向きは固定しない。
- 権限: FINE/COARSE_LOCATION、FOREGROUND_SERVICE(_LOCATION)、POST_NOTIFICATIONS、INTERNET、ACCESS_NETWORK_STATE、WAKE_LOCK、VIBRATE、
  （0.2.0〜、どちらも任意で設定画面から案内するだけ）ACCESS_BACKGROUND_LOCATION・REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
  前面で FGS を始めるので「使用中のみ」で画面オフ・裏でも記録できる。「常に許可」はアプリが落とされたときの自動再開にだけ効く。
  0.3.0〜 CAMERA（走行中の写真。撮影ボタンを初めて押したときに求める。QR の読み取りは Code Scanner なので無関係）、
  WRITE_EXTERNAL_STORAGE（maxSdk 28。9 以下でフォトライブラリに保存するときだけ）。Play に出すなら上の 2 つの任意権限はポリシーの申告が要る（`store/README.md`）。

## ライブラリ（`gradle/libs.versions.toml`）

AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Compose BOM 2024.12.01 / lifecycle 2.8.7 / navigation-compose 2.8.5、
Room 2.6.1（talk_compass と同じ）、kotlinx-serialization 1.7.3（バックアップの manifest）、
play-services-maps 19.0.0 + maps-compose 6.4.1 + maps-compose-widgets 6.4.1（縮尺バー）、Secrets Gradle Plugin 2.0.1（gps_camera と同じ）、
play-services-code-scanner 16.1.0 + play-services-base 18.5.0（QR。ModuleInstallClient）、play-services-location 21.3.0（0.9.0。Google の融合測位）、CameraX 1.4.1（core / camera2 / lifecycle / view）、
exifinterface 1.3.7。通信は HttpURLConnection（OkHttp は入れていない）。
テストだけ kxml2 2.3.0（XmlPullParser の実装）と sqlite-jdbc 3.46.1.3（Migration のテスト）。Gradle Wrapper 8.11.1。

## 構成

```
app/src/main/java/jp/kjtr/cycleroutelogger/
├── CycleApp.kt / MainActivity.kt   アプリ全体の 1 つずつ（DB・設定・測位・記録・案内）、GPX の「開く」「共有」受け取り
├── geo/      Geo（haversine）・RouteMatcher（外れ距離・進捗）・Bearing（方位・8 方位・画面端の矢印）・Simplify・AscentCounter（ルートの獲得標高）・
│             TrackSmoother（軌跡のなめらかさ。カルマン＋RTS・記録中の前向きのフィルタ。0.9.0）・
│             PlaceSearch（場所名の検索: Nominatim・1 秒の間隔・キャッシュ）
├── gpx/      GpxParser（XmlPullParser ストリーミング。trk の type も）・GpxWriter（1.1）・GpxTime（ISO 8601）
├── importer/ ファイルから記録を取り込む（0.10.0）: ActivityImporter（流れ・状態）・ActivityReader（zip / .gz / 形式の判定・取り込みの印）・FitParser・TcxParser・KmlParser・
│             RunCollector（線にまとめる）・FileSpool（点の一時ファイル）・DayPlanner（活動単位の重複・日付・slot・追加先）・ImportBuilder（記録と点・前の取り込みへの追加）・
│             ActivityCatalog（Strava の activities.csv。0.10.1）
├── plan/     WaypointType（種類と GPX の type/sym の推定）・WaypointsOnRoute（ルート沿いの並び・次の経由地）
├── draw/     描いたルート: DrawnShape（アンカー・ハンドル・区間の引き方の JSON）・Bezier（Cubic・de Casteljau・SegmentRouter / StraightRouter）・
│             RouteEditor（追加・挿入・移動・ハンドル・種類・元に戻す）・DrawnGeometry
├── photo/    PhotoLocator（時刻からの位置補間）・ExifTime / ExifGps・PhotoRepository（撮影の登録・Picker の取り込み・縮小版・削除・ライブラリ保存）・
│             ShareCardLayout（SNS 画像のレイアウト）・ShareCardRenderer
├── net/      RemoteUrls（共有リンクの書き換え）・RemoteContent（中身の判定・Drive の確認ページ）・RemoteGpxFetcher（https 取得）
├── record/   RideTracker（統計の純ロジック）・RecordingSession（Room に逐次保存・出来事）・RecordingService（FGS location。点の取り込みはここだけ）・
│             RecorderStatus（サービスの様子・開始の失敗）・TrackExport（記録 → ルート / GPX）
├── stats/    TrackFilter（記録の一覧の絞り込み・並べ替え・場所の判定と索引。0.11.0）・RideAnalysis（記録の詳細の区間（1 km / 5 km）・時速の分布（2 km/h ごと）の純ロジック）・
│             RideSummary（全記録のまとめ・開拓したマス GridCells。0.7.0）・CellShapes（開拓したマスの地図。0.8.0）・
│             ElevationGain（獲得標高。精度の悪い高度を除き中央値＋移動平均＋10 m のヒステリシス。0.9.0）
├── update/   UpdateCheck（版の比較・releases.json・間隔・UpdateChecker の純ロジック）・UpdateManager（取得・SharedPreferences `update`）
├── guide/    Navigator（案内・目的地・到着/接近/外れの知らせ）・Alerter（音・振動）
├── sensor/   LocationHub（LocationManager / Google の融合測位（0.9.0）。acquire/release）・HeadingProvider（ROTATION_VECTOR）
├── data/     Room（plan / route / route_point / waypoint / track / track_point / track_event / photo / import_source / track_geo）・PlanRepository・TrackRepository・SummaryRepository・SettingsStore・Backup・
│             TrackGeoIndex（場所の索引）・TrackBrowser（一覧の絞り込みの状態と保存）（0.11.0）
└── ui/       Home / Ride / PlanList / PlanEdit / Track(List|Detail) / Summary / Settings、MapParts・RidePanels・WaypointDialog・
              UrlImportUi（読み込み元の選択・QR・URL のダイアログ）・BackgroundRecording・TrackExportUi・CameraScreen・PhotoUi・ShareCardScreen・
              RouteDrawScreen・TrackImportScreen（0.10.0）・TrackFilterUi / TrackPlaceScreen（0.11.0）など
```

純ロジックは `app/src/test/` で単体テスト（GPX 解析・往復、距離・獲得標高・速度・自動一時停止・跳び、外れ距離・進捗、
経由地の並び・次の経由地、方位・方角・画面端の矢印、種類の推定、URL の書き換え・取得した中身の判定・Drive の確認ページ、Migration、記録 → ルート / GPX、
更新の確認（UpdateCheckTest）、区間の境目の補間・端数・一時停止の除外・分布の bin の境界・合計の一致（RideAnalysisTest、0.5.0）、場所名の検索の解析・URL・1 秒の間隔・キャッシュ（PlaceSearchTest、0.6.0）、
全記録のまとめの期間・集計・走行の時間帯・2 km/h の bin・開拓したマス（RideSummaryTest / GridCellsTest、0.7.0）、マスの矩形・まとめ・初めて開拓したマス（CellShapesTest、0.8.0）、軌跡のなめらかさ（TrackSmootherTest）・獲得標高（ElevationGainTest）・Migration 3→4（0.9.0）、ファイルからの取り込み（ActivityImportTest: FIT・GPX・TCX・KML・zip・.gz・日付・重複）・Migration 4→5（0.10.0）、活動単位の重複・取り込み済み・既存の記録への追加・activities.csv・Migration 5→6（0.10.1）、バックアップと記録のファイルの判定（FileKindTest）・まとめの地図のタイルの計算（MapTilesTest、0.10.2）、記録の絞り込み（期間の境界・タイムゾーン・夏時間・範囲・種類・名前・並べ替え・場所の円 / 矩形・総当たりとの一致・索引の保存と作り直し・条件の JSON。TrackFilterTest）・Migration 6→7（0.11.0）。
実際の書き出しで通す `ImportSampleTest` は zip（環境変数 `CRL_IMPORT_SAMPLE`）が無ければスキップ）。
ネットワークを使うテストは置かない。

## Room

- 版 7（0.11.0）。1 → 2 は `MIGRATION_1_2`（route / waypoint に sourceUrl、track_event 表）、2 → 3 は `MIGRATION_2_3`（route に editShape、photo 表）、
  3 → 4 は `MIGRATION_3_4`（track_point に altAccuracy・provider）、4 → 5 は `MIGRATION_4_5`（track に importFormat）、5 → 6 は `MIGRATION_5_6`（取り込みの印 import_source、track に CASCADE）、6 → 7 は `MIGRATION_6_7`（場所の索引 track_geo。表を作るだけで、既存の記録の索引は起動後に作る）。
  スキーマは `app/schemas/` に出力（コミットする）。**公開後は消さずに Migration を足す**（`AppDatabase.MIGRATIONS`。fallbackToDestructiveMigration は使わない）。
  Migration を足したら `MigrationTest` に倣って、前の版の JSON から作った SQLite に SQL を流して新しい JSON と突き合わせるテストを足す。

## Google Maps キー

- gps_camera と同じ方式（Secrets Gradle Plugin → Manifest の `com.google.android.geo.API_KEY`）。`local.properties`（git 管理外）に
  gps_camera / trip_planner と同じ `MAPS_API_KEY` をコピーしてある。**値をファイル・ログ・報告に出さないこと。**
- キーは Android アプリ制限付き。**`jp.kjtr.cycleroutelogger` + debug / release の SHA-1 を Cloud Console で追加しないと地図は灰色**
  （2026-10-11 時点で未登録。手順と SHA-1 は `store/README.md`）。灰色のときは地図の線・マーカーも描かれない
  （タイルだけでなくオーバーレイも出ない）。地図以外（案内・記録・統計）は動く。
- `onMapLoaded` はキーが通らないと来ないので、地図の準備は `MapEffect`（`ui/MapParts.kt` の `OnMapReady`）で判定し、
  範囲合わせは大きさを渡した `newLatLngBounds(bounds, w, h, pad)` を `move` で行う（animate は取り消される）。

## ビルド・確認

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd d:\work\android\cycle_route_logger
.\gradlew.bat test assembleRelease   # dist へのコピーは store/README.md
```

- エミュレータでは `adb emu geo fix <lon> <lat> <alt>` を 1 秒ごとに送ると記録できる（geo fix の速度は常に 0 なので、
  `RideTracker` は「端末の速度 0 でも 5 秒で 10 m 以上動いていれば位置を信じる」）。テスト用 GPX はスクリプトで作って
  `adb push` で Download に置き、SAF で開く。スクリーンショットは `captures/`（git 管理外）。
- **Pixel 実機には触らない**（ユーザー指示がない限り）。Wi-Fi で Pixel が adb に繋がっていることがあるので、adb は必ず `-s emulator-5554` を付ける。
- バックグラウンド記録・URL 取り込みの確認手順は `docs/DESIGN.md` §8。`am kill` は記録中（FGS）のプロセスを落とさないので、
  落とす確認は `adb -s emulator-5554 root` → `kill -9`。QR の読み取り画面はこのエミュレータ（GMS 24.23・Play ストア無し）では出ない（モジュールを入れられない）。

## 未実装・既知の制約

- 地図のサムネイル（記録一覧）は無し。速度の色分けは記録の詳細だけ。SNS 用の画像に区間・速度の分布は入らない。
- 区間・分布は記録の点から毎回計算する（Room に持たない）。バックアップから戻した記録は点の精度・端末の速度が無いので、
  一覧の統計（manifest の値）と区間の合計が少しずれることがある（0.9.0 からは設定の切り替え・上書きの最初の起動で点から計算し直すので、その後はそろう。
  読み込んだ直後はまだ manifest の値）。
- 獲得標高（0.9.0〜 ElevationGain）は前後 60 秒でならすので、短い坂（数十 m を数分）は 2〜3 割少なめに出る。記録中の表示は 1 分ほど遅れて増え、終えると数え直した値になる。
- Google の融合測位・高度の精度の効き目は実機で未確認（エミュレータでは geo fix がそのまま届くだけ）。
- 同じ道を往復するルートでは、外れ距離・進捗は「近い方」の区間に当たる（前回の進捗の近くを優先する処理は無い）。
- 進行方向が上の地図は、動いているとき（1.5 m/s 超かつ測位に方位がある）は進行方向、止まっているときは端末の向き。
  エミュレータの geo fix には方位が無いので、実機での確認が必要。
- OneDrive の共有リンクからの取得は実際のリンクで未確認（だめなら案内を出す作り）。
- ルートプラン・記録の詳細の地図は 85% まで広げると下の一覧がほぼ隠れる（全画面＋シートを使う想定）。
- 写真: Photo Picker は GPS の EXIF を消して渡すので、他のアプリの写真の位置はほぼ軌跡からの補間になる。アプリで撮った写真の本体はライブラリにあり、
  アンインストールしても残る（紐づけは消える）。バックアップに写真の紐づけは入らない。
- ルートを描く: 道に沿う方式は無い（`SegmentRouter` に足す設計だけ。DESIGN.md §4.4）。描いたルートは標高なし。
- 取り込み（0.10.0）: 実際の Strava の一括書き出し（FIT.gz 1615）は 0.10.1 で確認済み。Garmin Connect / Google Takeout の書き出しは未確認。Strava の GPX の数字の type は未確認。取り込みの印はバックアップに入らない（戻した後の再取り込みは軌跡の比較で外れる）。心拍・ケイデンスは捨てている。全体時間は最初〜最後の点なので、1 日に 2 回走った日は間の時間も入る。他アプリからの「開く」で zip を受けるのは無い。
- 配布は GitHub（`kijitora-no-hito/android-apps`）。`store/README.md`。
