# カバレッジ報告

測定日 2026年10月19日

## 測定結果

| 対象 | 現在の行 | 現在の分岐 |
| --- | --- | --- |
| Java | 97.13% | 85.82% |
| Python | 97.74% | 93.18% |

Java 21 / Python 3.11への移行版を2026年10月19日に測定しました。Javaはcommon-contractとprocessing-backendの両モジュール全本体を含みます。新API・Stub・寿命管理を含め、除外指定は追加していません。旧実行器の試験は新しい共通APIの取消/キュー試験へ置き換えました。

過去の移行前数値は履歴と[旧評価記録](validation/README.md)に残しています。ソース配置・Jacksonへの置換・新規接続層で計測対象が変わっており、旧値との単純な増減を品質の優劣とはしません。現在の[実測集計](validation/contract-migration-2026-10-19/coverage.json)を保存しています。

## 対象と計測方法

- Java：`common-contract/src/main/java/`と`processing-backend/src/main/java/`の全クラス。GradleとJaCoCo 0.8.14で測定。テストのclassファイルは別ディレクトリに置き、分母に含めません。
- Python：`python-service/src/editor_helper/`の全モジュール。coverage.py 7.16.2のbranchモードで測定。エントリーポイントも含みます。
- 本体の未通過コードをカバレッジ除外指定で隠していません。
- PyTorch・MobileSAM・psd-tools等の外部依存そのもの、開発用の`tools/`スクリプト、未実装のA側コードはこの本体コード計測の対象外です。
- Pythonの表は行と分岐を個別に計算しています。coverage.pyの端末表示の総合Cover列とは計算対象が異なります。

## Javaの領域別結果

| パッケージ（jp/ac/example/imageeditor以下） | 行 | 分岐 |
| --- | --- | --- |
| internal/processing | 98.80% | 94.94% |
| contract/psd | 100.00% | 100.00% |
| contract/common | 100.00% | 100.00% |
| integration/ai | 98.44% | 84.75% |
| contract/processing | 100.00% | 100.00% |
| processing | 97.83% | 75.00% |
| integration/stub | 97.87% | 75.00% |
| contract/ai | 100.00% | 100.00% |
| internal/integration | 96.07% | 85.21% |
| integration/shared | 100.00% | 91.67% |
| integration/psd | 97.44% | 100.00% |
| integration/runtime | 95.12% | 70.87% |
| cli | 88.24% | 83.33% |

## 追加した主なテスト

- 通信：認証・API版・要求ID・画像世代・応答サイズ・不正JSON・未知エラー・接続失敗・タイムアウト。
- AI応答：空候補、候補ID重複、不正score、マスクの色形式・原寸・二値性、応答順序の逆転と取消。
- 起動管理：破損・過大な通知、通知前の異常終了、子の再利用、終了済み子の再起動、SIGTERMを無視する子の強制停止。
- PSD：ZIPパス、スキーマ、資産参照、親循環、重複ID・順序、透明度、表示状態、個別レイヤーのないPSD、安全なグループと抽出失敗。
- キューとキャッシュ：LRU・TTL、削除中の推論、期限切れジョブの未実行、取消後の枠解放、実行中タイムアウトでの枠保持。
- モデル処理：manifest検証、ウォームアップ、埋め込み再利用、CPUへの切替と再計算、CPU側も失敗した場合の例外。

## 再現手順

依存の導入は[開発と使い方](usage.md)を参照してください。

```sh
python3 tools/coverage.py --check
```

模擬HTTPサービスとPython子プロセスを起動するため、127.0.0.1のローカル通信が必要です。モデル重みは不要です。Gradle Wrapper配布物はSHA-256を検証し、Java依存とJaCoCoはGradleが固定版を解決します。初回だけネットワークを使います。

生成先：

- `reports/coverage/java-html/index.html`：JavaのHTMLレポート
- `reports/coverage/python-html/index.html`：PythonのHTMLレポート
- `reports/coverage/java.xml`、`python.xml`：CI向けレポート
- `reports/coverage/summary.json`：行と分岐の集計値

生成レポートとツールキャッシュはGit対象外です。CIはレポートを成果物として保存します。

## CIの基準

Java・Pythonそれぞれについて、行95%以上・分岐85%以上を必要とします。計測値が下回れば`--check`が失敗します。クラウド上のCI実行結果はまだ未確認です。

## この数値が保証しないこと

モデルの通常テストは固定したテンソル・デバイスの代替実装を用います。推論オーケストレーションの分岐を確実に通すためのもので、ニューラルモデルの認識精度を測るテストではありません。実モデルとのJava結合は`python3 tools/test.py --integration`で別に検証します。

未通過箇所には、巨大ファイル・ZIPの上限、500ノード超過等の一部の資源上限、極端な起動通知・I/Oエラー等が残ります。すべての入力やOS障害を網羅したという意味ではありません。4K性能、実画像の切り抜き品質、実PSD互換性、A側のUI・履歴との受入は[担当Bの完了状況](b-completion-audit.md)で別管理します。

## 計測ツールの公式資料

- [JaCoCo CLI](https://www.jacoco.org/jacoco/trunk/doc/cli.html)
- [coverage.pyの分岐カバレッジ](https://coverage.readthedocs.io/en/latest/branch.html)
