# 開発と使い方

## 必要な環境

- JDK 21。JAVA_HOMEもJDK 21へ設定してください。
- Python 3.11系。検証版は3.11.16です。
- macOS。AIはMPS優先で、利用不可またはMPS演算失敗時はCPUを使います。

JavaはGradle Wrapper 8.10.2でビルドします。Jackson 2.18.12、JUnit 5.11.4等の版はgradle/libs.versions.tomlに固定しています。初回ビルドには依存取得のネットワーク接続が必要です。

以下はリポジトリのルートで実行します。AのJavaFXアプリケーションのソースはまだ本リポジトリにありません。現在使えるのはBの公開API、画像加工CLI、AI・PSDサービスです。

## Python環境

```sh
python3.11 -m venv python-service/.venv
python-service/.venv/bin/python -m pip install -r python-service/requirements.lock -r python-service/requirements-dev.lock
python-service/.venv/bin/python -m pip check
```

サービス・通常テストだけの場合はrequirements.lockの代わりにrequirements-core.lockを指定できます。実推論には完全なロックとモデルが必要です。3.11対応のためNumPyは2.3.5、NetworkXは3.4.2へ固定しています。MobileSAMのコードコミット・重みは変更していません。

```sh
python-service/.venv/bin/python tools/download_model.py
```

重み取得元とSHA-256はpython-service/models/model-manifest.jsonで確認します。サービス実行中の自動ダウンロードはありません。

## ビルドと試験

```sh
./gradlew checkBackend
python3 tools/test.py
python3 tools/coverage.py --check
```

checkBackendはJavaのJUnit試験、tools/test.pyはJava＋Pythonの通常試験です。Javaの独立した画素回帰・通信/PSD/起動管理の旧スイートは、内容を維持してJUnitから実行します。新しい公開APIの取消・世代・型変換・サービス寿命もJUnitで検証します。

通常試験は実AI重みを読みません。模擬HTTPと子プロセスのため127.0.0.1の通信許可が必要です。Pythonのみの実行は次のとおりです。

```sh
PYTHONPATH=python-service/src python-service/.venv/bin/python -m unittest discover -s python-service/tests -v
```

実際の共通API→Java管理下のPython 3.11→MobileSAM→型付きPSDまでの結合：

```sh
python3 tools/test.py --integration
```

レポートはprocessing-backend/build/reports/tests/test、processing-backend/build/reports/jacoco/test、reports/coverageに生成します。Javaはcommon-contractとprocessing-backendの全本体コードを計測し、Pythonとともに行95%以上・分岐85%以上をチェックします。

## 画像加工CLI

```sh
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=gray.png -Poperation=GRAYSCALE
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=resized.png -Poperation=RESIZE -Pparameters="800 600"
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=cropped.png -Poperation=CROP -Pparameters="10 20 200 150"
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=adjusted.png -Poperation=COLOR_ADJUST -Pparameters="20 1.2 0.8"
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=blurred.png -Poperation=BLUR -Pparameters="3"
```

ROTATE_RIGHT、ROTATE_LEFT、FLIP_H、FLIP_V、SEPIA、NEGATIVE、MOSAICも利用できます。MOSAICのparametersはブロック寸法（例12）です。入力名は自分の画像へ置き換えてください。出力はPNGで、既存ファイルは上書きしません。CLIも共通ImageProcessorを使います。

## A側から使う

共通型はjp.ac.example.imageeditor.contract以下です。JavaFXに依存せず、common-contractはJacksonにも依存しません。正確な型は[共通contract設計書](design/共通contract設計書.md)とcommon-contract/src/main/javaを参照してください。

- 画像処理：DefaultImageProcessorをアプリ構成箇所で作り、共通ImageProcessorをCoordinatorへ渡します。submitはAsyncOperationを返し、stageで結果を受けcancelで取り消します。
- サービス：integration.runtime.LocalServiceManagerをアプリ当たり1つ所有します。ServiceConfigurationへPython・サービスdir・manifest・log先を渡してstartします。
- AI：manager.aiService()で共通AiSegmentationServiceを取得。registerImage、segment(AiRequest)、releaseImage(RegisteredImage)、invalidateRequestsを使います。
- PSD：manager.psdService().importPsd(path)で型付きPsdImportResultを受け取ります。
- 終了：画像処理とLocalServiceManagerのcloseを呼びます。サービスcloseは最終終了で、再起動にはrestartを使います。

開始・終了の待機をUIスレッドで行わず、完了はAがUIスレッドへ配送します。Aは結果適用前に対象・contentRevision・AI session/requestIdを再検証してください。stage().toCompletableFuture()の変更は内部操作へ影響せず、取消はcancel()だけが入口です。取消・失敗は領域別のchecked例外をstageの原因として返します。

start成功は通知とhealth確認までです。初期のモデルimportでhealthが一時的に遅れる場合に限り、1回2秒・全体10秒の有界health照会を行います。モデル準備は別に監視し、status().aiReady/aiErrorを確認します。AIの準備待ちが超過しても動作中のPSDサービスを停止しません。

Aの設定読込・UI・Document/History/.jimgとの共同結合は未実施です。BがAの指定保存先を直接決めず、Aが設定ファイルから解決したlogDirectoryを渡します。Bのログは許可したイベントだけを保存し、生stderr・Token・画像・クリック列を保存しません。

## A側を先行開発するStub

integration.stub.StubBackendはImageProcessor、AiSegmentationService、PsdImportServiceを実装します。呼出し後にtake(timeout, unit)でPendingを取得し、succeedまたはfailで任意の順序で完了させられます。AsyncOperation.cancel、旧AI要求の失効、closeも使用できます。実モデルやHTTPなしでAの成功・失敗・取消・旧結果の破棄を検証できます。Stubは表示・履歴を変更しません。

## サービスを単独起動

```sh
export EDITOR_HELPER_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
PYTHONPATH=python-service/src python-service/.venv/bin/python -m editor_helper \
  --host 127.0.0.1 --port 0 --model-manifest python-service/models/model-manifest.json
```

動的ポート・PID・instanceを含む通知が標準出力へ1行出ます。終了はCtrl+C。HTTP API v1のパスとJSON/PNG/ZIP形式は維持しています。

| API | 用途 |
| --- | --- |
| GET /health | aiReady、psdReady、device、aiError |
| POST /image | PNG＋sourceRevisionの登録 |
| POST /segment | 原寸の正負クリック列で1～3候補 |
| DELETE /image/{id} | 元画像と埋め込みの解放 |
| POST /psd/import | PSDからmanifest＋RGBA PNGのZIP |

各要求にAuthorization: BearerとX-Api-Version: 1を付け、X-Request-IdはUUIDを指定します。segmentのJSON requestIdはヘッダーと一致させます。

## 実モデル・新規環境・性能の確認

```sh
python-service/.venv/bin/python tools/model_smoke.py
python-service/.venv/bin/python tools/model_smoke.py --cpu
python-service/.venv/bin/python tools/model_smoke.py --fallback
python-service/.venv/bin/python tools/verify_clean_environment.py --ai
./gradlew :processing-backend:benchmark
```

--cpuは準備済みバックエンドをCPUへ切り替えて実CPU推論します。--fallbackはMPS失敗を1回注入し実CPUで再推論します。性能計測はCIの時間制限に使いません。過去の性能・写真評価は[評価記録](validation/README.md)、移行版の確認は[移行実装記録](contract-migration.md)に記録しています。

## 書式

```sh
python3 tools/format.py
python3 tools/format.py --check
```

Javaは固定Google Java Format、Pythonは固定Ruff。初回だけ整形ツールを取得しSHA-256を検証します。build/test等の補助スクリプトもGradle Wrapperを呼び、旧app/buildへは生成しません。
