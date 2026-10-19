# 共通contract・実行環境への移行実装記録

確認日：2026年10月19日。Aの詳細設計と回答に沿って、B単独で進められる公開API・ビルド・実行環境・接続層を実装しました。Aとの最終相互レビューと、Aの画面・モデル・履歴を使う共同受入は未完了です。

## 実装した範囲

- common-contract：JavaFX/Jackson非依存の領域別interface、record、AsyncOperation、共通ErrorCode、checked例外、型付きPSDと深い不変metadata。
- processing-backend：公開DefaultImageProcessor、AI/PSD facade、手動完了可能なStubBackend、サービスのstart/restart/最終close、CLI、JUnit。
- Java 21 / Gradle Wrapper 8.10.2。Wrapperの配布SHA-256も固定。CatalogへJackson 2.18.12、JUnit 5.11.4とPlatform 1.11.4を固定。
- Python 3.11.16。元のlockで3.11非対応だったNumPy 2.5.4とNetworkX 3.7を、対応する2.3.5 / 3.4.2へ変更。他のモデル関連版・MobileSAMコミット・重みSHA-256を維持。
- 旧app/のBコードを新moduleへ移動。検証済み画素演算とHTTP/ZIPの内部型はinternalへ閉じ込め、独自JSONパーサーをJacksonへ置換。重複して不要になった旧画像処理実行器は除去し、取消/キューの試験を新公開APIへ移行。

editor-appはAのソース受領先の配置枠だけです。JavaFX画面・Document・History・保存をBで重複実装していません。

## 公開APIで保証すること

AsyncOperation.stage()は内部結果を外から完了・取消できないビューです。取消はcancel()が唯一の入口で、最初の受付だけtrue、完了後と二度目以降はfalseです。取消後は領域別checked例外(code=CANCELLED)で失敗完了し、後から画像を公開しません。モデル演算は即時中断せず、Python側の枠を実終了まで保持します。

AI要求はserviceInstanceIdへ結び付けます。旧instanceの画像を新サービスへ送らず再登録を促し、旧instanceへの解放は現在サービスへDELETEを送らず成功扱いにします。取消後に登録IDが判明すれば解放を試み、ID不明ならサーバーのLRU/TTLで回収します。AはUI反映直前にも対象・contentRevision・session/requestIdを確認します。

PSDはtyped PsdNode / RasterAsset / ImportWarningを返し、元属性の未知フィールドはMetadataValueで保持します。未知warningはUNKNOWNと元codeを保持します。HTTP v1 / ZIP schemaVersion=1を変えず、Java recordの直列化を通信仕様にしません。返却補助ノードを含む500件の上限、未参照のラスタ資産拒否も実装しています。500件の数え方はAへ確認する具体案として明示しています。

LocalServiceManagerは安定したAI/PSD facadeを返し、start成功はHTTP到達まで、モデル準備は別監視です。実結合でモデルimport中の最初のhealthが2秒を超えるケースを確認したため、起動直後の一時TIMEOUTだけは1回2秒・全体10秒の有界照会としました。画像登録・推論等の自動再送はしません。restartで旧instanceを失効し、最終closeで子、reader、HTTPと所有実行器を終了します。

ログは注入された保存先へSERVICE_STARTED等の許可イベントだけを書き、5MiB×3世代で保持します。生stderr、Token、画像、クリック列は保存しません。ログ保存失敗で通常編集を止めません。

## 検証結果

| 検証 | 結果 |
| --- | --- |
| Java/JUnit | 新公開API試験と既存回帰スイートを含む21件が成功 |
| Python | 3.11.16で通常試験50件が成功 |
| 実MPS | 共通API→所有子→MobileSAMで3候補、追加正負クリック、画像解放が成功 |
| 実CPU | CPU推論と、MPS例外を1回注入した後の実CPU再推論で3候補 |
| PSD | 共通APIから型付き結果・資産を取得。上限、階層、未知warning、未参照資産を検証 |
| 環境再現 | 新規3.11 venvへ完全lock導入、pip check、通常試験、起動・モデル準備・実推論・終了 |
| カバレッジ | common-contract＋processing-backend全本体とPython全本体を対象とし、行95%・分岐85%の基準を通過。除外追加なし |

具体値は[カバレッジ報告](coverage.md)と[実測集計](validation/contract-migration-2026-10-19/coverage.json)、環境再現は[結果JSON](validation/contract-migration-2026-10-19/clean-environment-ai.json)を参照してください。GitHub上のCI実行結果は未確認です。

## Java 21での性能再測定

Apple M1 Pro / 32GiB / macOS 27.0.1、Temurin 21.0.12.1+1、ヒープ上限2GiB。従来と同じ半透明パターン画像、初回1回＋追加3回で測定しました。

| 対象 | 2K中央値 | 4K中央値 |
| --- | ---: | ---: |
| Gaussian blur 最大radius=20 | 339.2ms | 1295.7ms |

[全12操作の生データ](validation/contract-migration-2026-10-19/java21.json)も保存しています。この性能測定は内部の同じ画素演算を対象とし、HTTPやA画面の操作時間ではありません。時間の変動をCIの成否には使用しません。MPSの品質・PSDの互換性・UI応答をこの値だけで合格とはしません。

## Aへ渡す入口

共通型はcommon-contract/src/main/java/jp/ac/example/imageeditor/contract以下です。アプリ構成箇所でDefaultImageProcessorとLocalServiceManagerを所有し、CoordinatorへはImageProcessor / AiSegmentationService / PsdImportServiceを注入します。サービスから取得したfacadeを再起動後も保持できますが、古いRegisteredImageは保持できません。

先行画面試験はStubBackendを利用します。submit/register/segment/import等の後、take(timeout, unit)でPendingを受け取り、独立した固定画像を含む結果でsucceed、領域別例外でfailします。順序を逆にした完了、取消、旧結果をAの画面・履歴が正しく扱うかを実モデルなしで確認できます。

ビルド・CLI・実サービス結合・再現のコマンドは[開発と使い方](usage.md)を参照してください。

## 残る共同確認

- Aの既存common-contract/Gradle/Version Catalog/Stubとの重複や署名の調整、追加具体案の相互レビュー。
- AのUIからの最小結合とI01～I05。Document/Layer・世代/session・履歴予算・dirty・警告確認・.jimg保存はA所有。
- JavaFXの正確なpatch版とアプリの設定/ログ保存先読込の接続。Bは設定ファイルを直接作成しません。
- 多様な実PSDと写真の品質、数時間の運用、別PCでの再現。旧[品質評価](validation/README.md)は参考記録として保持。

Bの実装があることと、Aもこの署名・具体値を採用済みであることは区別します。[Aへの確認事項](coordination/共通contract具体案の確認事項.md)を用いて接続前にレビューできます。
