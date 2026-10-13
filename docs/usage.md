# 開発と使い方

## 必要な環境

- JDK 17以上（Javaのコンパイル対象は17）
- Python 3.12（依存ロックを作成・検証した版は3.12.14）
- macOS。AIはMPSを優先し、利用できない場合やMPS推論失敗時はCPUで実行します。

JavaFXを使うエディタ画面は未実装です。現在は画像加工CLIと、AI・PSD用のPythonサービスを実行できます。ビルドツールに依存しないB側の検証用手順を用意しています。

以下のコマンドはリポジトリのルートで実行します。

## Javaのビルドとテスト

```sh
python3 tools/build.py --test
```

`app/build/classes/`へJavaコードをコンパイルし、画像処理と共通処理の回帰テストを実行します。Javaの追加ライブラリは不要です。テストしない場合は`--test`を省略します。

## 画像を加工する

```sh
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png gray.png GRAYSCALE
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png rotated.png ROTATE_RIGHT
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png resized.png RESIZE 800 600
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png cropped.png CROP 10 20 200 150
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png adjusted.png COLOR_ADJUST 20 1.2 0.8
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png blurred.png BLUR 3
java -Djava.awt.headless=true -cp app/build/classes editor.cli.Main input.png mosaic.png MOSAIC 12
```

入力ファイルは自分の画像に置き換えてください。出力はPNGです。既存の出力ファイルは上書きせずエラーにします。`ROTATE_LEFT`、`FLIP_H`、`FLIP_V`、`SEPIA`、`NEGATIVE`も使用できます。CLIは担当Bの処理を検証する入口で、担当Aの保存処理や履歴を置き換えるものではありません。

## Python環境を用意する

```sh
python3.12 -m venv python-service/.venv
python-service/.venv/bin/python -m pip install -r python-service/requirements.lock
```

サービスと通常テストだけを先に試す場合は、代わりに`python-service/requirements-core.lock`を指定できます。その構成ではAIは利用不可となり、実推論には完全なロックの導入が必要です。

このロックには実推論用のPyTorch・torchvision・timmと、コミットを固定したMobileSAMも含まれます。依存導入にはネットワーク接続が必要です。仮想環境はGit対象外です。

モデル重みを明示的に取得します。

```sh
python-service/.venv/bin/python tools/download_model.py
```

取得先とSHA-256は`python-service/models/model-manifest.json`に固定しています。重みはGit対象外です。サービス実行中に重みを自動取得することはありません。

## Pythonのテスト

```sh
PYTHONPATH=python-service/src python-service/.venv/bin/python -m unittest discover -s python-service/tests -v
```

通常テストでは固定バックエンドを使用し、重みをロードしません。PSDはテスト内で生成して検証します。

Javaの追加テストは127.0.0.1の模擬HTTPサービスと一時的なPython子プロセスを起動します。通常テストにAI重みや外部ネットワークは不要ですが、ローカル通信を許可した環境で実行してください。

JavaとPythonをまとめて確認する場合：

```sh
python3 tools/test.py
```

実際のJava→Python→MobileSAM推論とPSD解析の結合テスト：

```sh
python3 tools/test.py --integration
```

`--integration`にはモデル重みと127.0.0.1でのローカル通信が必要です。テストは子サービスを起動し、図形画像から候補取得・追加正負クリック・画像解放・PSD取り込みを確認した後、サービスを終了します。テスト用PSDはGit対象外の`tmp/`へ生成します。

実モデルの単体確認もできます。

```sh
python-service/.venv/bin/python tools/model_smoke.py
python-service/.venv/bin/python tools/model_smoke.py --fallback
```

`--fallback`はMPSが使える環境で、MPSの失敗を意図的に1回発生させ、実際のCPUモデルで再推論します。MPSが利用できない場合は実行しません。

## Pythonサービスを単独で起動する

```sh
export EDITOR_HELPER_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
PYTHONPATH=python-service/src python-service/.venv/bin/python -m editor_helper \
  --host 127.0.0.1 --port 0 \
  --model-manifest python-service/models/model-manifest.json
```

HTTPの受付準備ができると、ポート・PID・serviceInstanceIdを含むJSONが標準出力に1行出ます。モデルの準備は別に進みます。終了はCtrl+Cです。

各HTTP要求に`Authorization: Bearer <EDITOR_HELPER_TOKEN>`、`X-Api-Version: 1`を付けます。`X-Request-Id`はUUIDを指定でき、省略時はサービス側で発行します。segmentではJSONのrequestIdと同じUUIDをヘッダーに指定してください。

| API | 用途 |
| --- | --- |
| GET /health | aiReady、psdReady、device、aiErrorの確認 |
| POST /image | PNGとsourceRevisionをmultipartで登録 |
| POST /segment | imageId・sourceRevision・requestId・クリック列で最大3候補を取得 |
| DELETE /image/{id} | 画像と埋め込みを解放 |
| POST /psd/import | PSDをmultipartで送り、中間DTOとRGBAをZIPで取得 |

モデルがない場合、AIは利用不可になりますが、healthとPSD取り込みは継続して利用できます。

## Javaから接続する

`LocalServiceManager.start(pythonExecutable, serviceDirectory, modelManifest)`で子サービスを起動し、返された`HelperClient`を使用します。起動・画像処理をJavaFXのUIスレッドで実行しないでください。

- `health()`でモデルの準備を確認します。
- `registerImage(snapshot, sourceRevision)`で登録します。
- `segment(imageId, sourceRevision, requestId, points)`で候補を取得します。
- `cancelSegmentation(imageId)`で旧応答を無効にします。
- `releaseImage(imageId)`でキャッシュを解放します。
- `importPsd(path)`でPSD中間結果を取得します。
- アプリ終了時は`LocalServiceManager.dispose()`を呼びます。

非同期呼出しは取消可能なCompletableFutureを返します。UIへ反映する際は、担当A側でも対象・画像世代・最新requestIdを確認してください。詳細は[実装状況](implementation.md)と[担当Aへの確認事項](coordination/担当Aとの合意事項.md)を参照してください。

## 残っている結合と受入

エディタ画面、レイヤー登録、Undo/Redo、PNG/JPEG書き出しを含む一連の受入は担当A側の実装と結合後に行います。実推論の動作確認と、人物・動物・日用品を使った切り抜き品質の受入は別に評価します。

## カバレッジを計測する

```sh
python3 tools/coverage.py --check
```

JavaはJaCoCo、Pythonはcoverage.pyで、本体コードの行と分岐を測定します。初回だけSHA-256を固定したJaCoCoを取得するためネットワークが必要です。以後は`.cache/jacoco/`を使用します。レポートはGit対象外の`reports/coverage/`へ生成します。

CIではJava・Pythonそれぞれの行95%以上、分岐85%以上を基準とし、下回ると失敗します。計測対象・残っている未通過箇所・実モデル評価との違いは[カバレッジ報告](coverage.md)を参照してください。

## コードの書式を揃える

開発用依存を追加します。実推論用依存は必要に応じて上記の完全なロックから導入してください。

```sh
python-service/.venv/bin/python -m pip install -r python-service/requirements-dev.lock
python3 tools/format.py
python3 tools/format.py --check
```

JavaはGoogle Java Format、PythonはRuffで統一します。Javaの整形ツールは初回に取得し、`tools/java-format.json`のSHA-256で検証してキャッシュします。CIでも書式と未使用import・変数を確認します。

## 性能・品質・環境再現を評価する

[担当Bの評価記録](validation/README.md)に、計測条件、実写真・PSDの確認結果、再現手順を記載しています。性能計測は通常テストとは別に実行し、時間の変動をCIの成否には使いません。
