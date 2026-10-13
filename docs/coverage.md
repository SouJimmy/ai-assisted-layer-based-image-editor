# カバレッジ報告

測定日 2026年10月13日

## 測定結果

| 対象 | 更新前の行 | 更新後の行 | 更新前の分岐 | 更新後の分岐 |
| --- | --- | --- | --- | --- |
| Java | 49.81% | 97.77% | 38.96% | 89.22% |
| Python | 74.47% | 98.72% | 65.25% | 90.68% |

更新前はmainの`07ea574`に対する従来テストの実測値です。更新後は追加テストと、そこで見つかったnull入力・サービス再起動の修正を含む実測値です。

Javaは従来の回帰テストに通信・PSD・起動管理・CLIの追加スイートを加えました。Pythonは13ケースから45ケースへ増やしました。数値はカバレッジであり、テストの実行回数やチェック数から推定した値ではありません。

## 対象と計測方法

- Java：`app/src/main/java/`の全クラス。JaCoCo 0.8.14で測定。テストのclassファイルは別ディレクトリに置き、分母に含めません。
- Python：`python-service/src/editor_helper/`の全モジュール。coverage.py 7.16.2のbranchモードで測定。エントリーポイントも含みます。
- 本体の未通過コードをカバレッジ除外指定で隠していません。
- PyTorch・MobileSAM・psd-tools等の外部依存そのもの、開発用の`tools/`スクリプト、未実装のA側コードはこの本体コード計測の対象外です。
- Pythonの表は行と分岐を個別に計算しています。coverage.pyの端末表示の総合Cover列とは計算対象が異なります。

## Javaの領域別結果

| パッケージ | 行 | 分岐 |
| --- | --- | --- |
| editor/integration | 97.08% | 87.16% |
| editor/cli | 94.44% | 83.33% |
| editor/processing | 99.06% | 94.57% |

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

模擬HTTPサービスとPython子プロセスを起動するため、127.0.0.1のローカル通信が必要です。モデル重みは不要です。JaCoCoは初回取得時だけネットワークを使い、取得物のSHA-256を`tools/jacoco.json`で検証します。

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
