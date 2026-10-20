# コードの可読性とテストの確認

確認日：2026年10月20日

## 確認結果

公開API、Java側の画像処理・AI通信・PSD変換・起動管理、Python側のキュー・キャッシュ・モデル接続・PSD抽出を確認しました。責務ごとの配置と書式は概ね整理されています。同名の内部実装と公開窓口があるため、下の案内ではモジュールとパッケージを区別しています。

非同期処理では、完了通知が呼び出し元の処理をその場で実行することに注意が必要です。OperationPromise、サービス起動、AI要求の失効、Stubの終了で、ロックを保持したまま通知していた箇所を修正しました。コールバックが別スレッド経由で同じサービスを利用すると、互いの終了を待つ可能性がありました。

結果の決定と通知を分離し、成功・失敗・取消のうち一つだけを確定します。AIでは旧要求の除去と新要求の受付順序をロックで保護し、旧要求の取消通知はロックを解放してから実行します。コメントはこの理由を説明する箇所に限定しました。

## コードを読む順序

Javaのパッケージ名は `jp.ac.example.imageeditor` からの相対名です。

| 目的 | 入口と読む順序 |
| --- | --- |
| 公開契約 | `common-contract` の `contract.processing` / `contract.ai` / `contract.psd`。非同期ハンドルとエラーは `contract.common` |
| 画像処理 | `processing-backend` の `processing.DefaultImageProcessor` → `internal.processing.ImageProcessor` → `Processing` / `Filters` |
| AI通信 | `integration.ai.HttpAiSegmentationService` → `internal.integration.HelperClient`。公開DTOへの変換、起動世代、旧要求失効は前者、HTTPと応答検証は後者 |
| PSD通信と変換 | `integration.psd.HttpPsdImportService` → `internal.integration.HelperClient` → `PsdArchiveReader` / `PngDecoder` |
| 起動・停止 | `integration.runtime.LocalServiceManager` → `internal.integration.LocalServiceManager`。前者は公開状態と非同期の寿命管理、後者は子プロセス・通知・HTTP接続の所有者 |
| 非同期の結果決定 | `integration.shared.OperationPromise`。完了と取消の勝者を決め、ロックの外で通知 |
| UI結合用の代替実装 | `integration.stub.StubBackend`。テスト側で成功・失敗・取消・応答順序を制御 |
| PythonのHTTP処理 | `python-service/src/editor_helper/api.py` → `work_queue.py` |
| モデルとキャッシュ | `model_runtime.py`、`image_cache.py`。通常の操作は単一worker、削除印だけはAPI側からロックで保護して更新 |
| PSDの抽出と代替合成 | `psd_importer.py`。ノード収集 → 安全な合成範囲の決定 → 資産抽出 → ZIP生成 |

JavaFXの画面・編集モデル・履歴はA側の担当です。本リポジトリの `editor-app` は配置枠であり、Bのコードを読む際の起点にはしません。

## テストとコメントの判断

画素処理の独立した期待値、通信契約、取消・期限、PSDの安全な取り込みを検証するテストは維持しています。既存の `LegacyRegressionTest` は多数の独立した画素・通信検証をJUnitから実行する窓口です。公開APIのテストと確認する境界が異なるため、重複として削除していません。

今回追加した回帰テストは、通知時にロックを保持しないこと、起動完了の通知から別スレッド経由で管理クラスを利用できること、AIの明示失効と新要求による置換、Stub終了を対象とします。成功・失敗・取消を100回競合させ、一つだけが勝つことも確認します。未設定・終了後のrestartは既存の寿命管理テストに統合し、同じ確認だけを繰り返す別テストは作っていません。

画像の所有権、半透明の計算、期限切れ後の実行枠、PSDの代替合成など、理由を伝えるコメントは必要です。今回確認した範囲に、大量の不要コメントや削除すべきテストは見つかりませんでした。単なる行数削減のための再分割やカバレッジ除外は行っていません。

## 検証と残る境界

全本体の行・分岐カバレッジ、テストの結果と再現手順は[カバレッジ報告](coverage.md)を参照してください。カバレッジの数値はモデルの認識精度やPSD完全互換を保証しません。

PSD単体と実モデルの資源測定は[資源評価](validation/resources-2026-10-20/README.md)に記録しています。Photoshop由来の実PSD、多様な写真と正解マスク、数時間の運用、別PCでの再現、Aの実画面を通す受入は引き続き確認が必要です。
