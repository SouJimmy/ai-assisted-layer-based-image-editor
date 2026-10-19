# 担当Bの実装状況

確認日：2026年10月19日。A回答に沿う共通API・環境移行の実装を反映しました。相互レビューの完了やAの実画面での受入とは区別します。

## 実装済み

| 領域 | 現在の実装 |
| --- | --- |
| 公開API | common-contractのcontract.common / processing / ai / psd。JavaFX・Jackson・Aモデルへの依存なし |
| ビルド | Java 21、Gradle Wrapper 8.10.2、Catalog、Jackson 2.18.12、JUnit 5.11.4 |
| 画像処理 | DefaultImageProcessorの同期process・非同期submit。Crop/回転/反転/Resize/色/基本フィルター/ぼかし/モザイク |
| 所有権と結果 | 独立ARGB_PRE、半透明の式・丸めを維持。operation/parameters/変更範囲/寸法変更/決定性/algorithmVersion |
| 非同期 | 読取用stage、取消ハンドル、領域別checked例外、待機枠解放、取消後結果抑止 |
| AI | 型付き登録・要求・候補・結果。起動instanceの検証、旧要求失効、取消後登録の解放、キャッシュの明示解放 |
| PSD | 型付きPsdNode/資産/警告、深い不変metadata、未知warning保持、ZIP検証、返却補助ノード込み500件上限 |
| 寿命管理 | 安定したAI/PSD facade、非同期start/restart、healthとモデル準備の分離、最終close、旧instance失効 |
| ログ | 注入された保存先へ許可イベントのみを記録、5MiB×3世代。生stderr・Token・画像・クリック列は保存しない |
| Stub | 手動で成功・失敗・取消・順序逆転を作れるStubBackend |
| Python | 3.11.16。NumPy 2.3.5 / NetworkX 3.4.2で互換性を確認しlockを更新 |
| 内部処理 | 検証済みの画素処理・HTTP/ZIP実装をinternalへ配置し、新しい公開境界から利用。独自JSONパーサーはJacksonへ置換 |

PythonのMobileSAMコミット・重みSHA-256、HTTP API v1、PSD ZIP schemaVersion=1は維持しています。画像処理algorithmVersionはv1で、API移行だけによる演算式変更はありません。

## 確認済み

- Java 21で共通型とB実装をビルドし、JUnitで既存の独立画素回帰・通信/PSD/プロセス境界と、新しい共通APIの試験を実行。
- Python 3.11の通常試験、実MPS推論、実CPU推論、MPS例外後の実CPU再試行。
- 共通API→所有子サービス→MobileSAMの候補取得・追加正負クリック・解放→型付きPSDの結合。
- 新規Python 3.11環境で完全lockの導入、pip check、起動・実推論・終了の再現。
- common-contractとprocessing-backendを含むJava全本体、Python全本体の行・分岐カバレッジ。

具体値と再現手順は[移行実装記録](contract-migration.md)、[カバレッジ報告](coverage.md)、[開発と使い方](usage.md)を参照してください。

## 残る共同作業と制限

AのJavaFXアプリ・Coordinator・Document/Layer/History・保存コードはまだ本リポジトリにありません。editor-appは配置枠のみです。Aの画面からの接続、世代/sessionの最終確認、履歴・dirty、PSD警告UIと.jimg保存を含むI01～I05は共同結合後に確認します。

共通APIの具体署名・補助型・ノード上限の数え方等は[追加確認事項](coordination/共通contract具体案の確認事項.md)としてAとの相互レビュー待ちです。実装済みという理由でAも採用済みとは扱いません。GitHub上のCI実行結果も未確認です。

PSDは安全な最小normalグループで代替合成し、背景依存時は祖先または文書全体へ広げます。原情報を残したまま補助ノードを追加して500件を超える場合はRESOURCE_LIMITです。Photoshop由来の調整・効果等を含む多様な実PSDでの互換性受入は継続課題です。

実写真3例では輪郭欠け・背景混入が残ります。より多様な写真・正解マスクでの品質評価、数時間の運用、別評価PCでの再現は未完了です。過去の[評価記録](validation/README.md)は測定時のJava 17 / Python 3.12環境の結果として保持しています。
