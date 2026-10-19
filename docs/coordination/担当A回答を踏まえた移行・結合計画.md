# 担当A回答を踏まえた移行・結合計画

作成日：2026年10月19日。対象：Version 1、担当Bの実装移行と担当Aとの結合。

## 1. 方針

次に優先するのは機能の追加ではなく、検証済みのB処理系をAが呼べる共通contractへ接続することです。画像処理・HTTP API v1・AI推論・PSD内部処理はできる限り維持し、公開型、実行環境、配置、取消・例外の境界を順に移行します。

この文書は実装着手前の計画です。Aからの回答受領を、移行実装の完了や、全項目について双方の最終確認が済んだこととは扱いません。共通contract・ビルド・依存等の変更は、具体的な差分をレビューできる形にして相互確認します。Aへの送信や日程の確定は行っていません。

参照資料：

- [担当A詳細設計](../references/プログラミング言語_詳細設計_担当A.pdf)：全29ページ。特に2～4、8～13、14～16章。
- [AG-01～09 担当A回答](../references/合意事項_担当A回答.pdf)：全8ページ。
- [Bの現在の実装状況](../implementation.md)、[残タスク](../b-completion-audit.md)、[評価記録](../validation/README.md)。

比較対象のコードは本リポジトリの`baca6e4`です。現チェックアウトにはAの`editor-app`やGradle構成、共通contractの実コードはまだありません。Aの設計書にクラス名があっても、実装済みとは判断していません。

## 設計改訂の進捗（2026年10月19日）

[担当B詳細設計](../design/担当B_詳細設計書.md)をA回答へ合わせ、[共通contract設計書](../design/共通contract設計書.md)でP1の型・署名・取消・例外・寿命管理を具体化しました。A回答にない追加案は[確認事項](共通contract具体案の確認事項.md)へ分けています。その後Javaソース・Stub・コンパイル試験まで実装しました。相互レビューは未完了です。下表の「現在のB」は移行前コードの説明です。

### B単独の実装進捗

共通型ソース、Stub、JUnit契約試験、Java 21/Gradleへの配置、Python 3.11.16互換性、Processor/AI/PSD接続、Jackson、サービス寿命管理を実装しました。P1～P4のB単独実装・検証に相当します。相互レビューとP5～P7のA画面・モデル・履歴を通す結合は未完了です。[移行実装記録](../contract-migration.md)を参照してください。以下の差分表は計画時の比較対象を保持しています。

## 2. 回答と現実装の差分

| 項目 | Aの回答・詳細設計 | 現在のB | 必要な対応 |
| --- | --- | --- | --- |
| Java | Java 21、JavaFX 21系 | Java 17、JavaFX非依存 | Java 21 toolchainへ移行。BとcontractはJavaFX非依存を維持 |
| モジュール | editor-app / common-contract / processing-backend / python-service | app/にBのCLI・処理・連携を配置 | app/のBコードをprocessing-backendへ移動。editor-appはA所有 |
| パッケージ | jp.ac.example.imageeditor以下 | editor.processing / editor.integration | 新ルートと公開contractへ移行。B実装のサブパッケージ名はレビュー時に具体化 |
| ビルド・試験 | Gradle Wrapper 8.10.2、Kotlin DSL、JUnit 5 | 補助Pythonによるjavacとmain形式の検証 | Gradleへ統合し、既存の検証をJUnitから実行可能にする |
| JSON | Jackson 2.18系、patch版をVersion Catalogで固定 | 独自Jsonクラス | HTTP/ZIPの上限・型検証を保ちつつJacksonへ移行 |
| 非同期 | AsyncOperation<T>：stage() / cancel() | CompletableFutureを直接公開 | 公開境界を共通ハンドルへ変更。内部Futureの直接変更を外へ公開しない |
| 例外 | ErrorCodeを持つchecked例外3種 | Processing.Failure / ServiceFailureはunchecked、サービスcodeはString | ProcessingException / AiServiceException / PsdImportExceptionと共通ErrorCodeへの変換 |
| 処理結果 | operation、parameters、変更範囲、寸法変更有無、決定性、algorithmVersion | Processing.Resultに相当情報あり | 演算式を変えず、共通型へ移す |
| AI登録 | imageId、sourceRevision、寸法、serviceInstanceId | HelperClient.RegisteredImageに同項目あり | 共通型へ移す。AのsourceRevision生成規則を利用 |
| 画像世代 | AがdocumentIdと単調増加contentRevisionを管理 | BはsourceRevisionを不透明な文字列として保持 | AはdocumentId + ":" + contentRevisionを生成、Bは内容を解釈せず一致検証 |
| PSD | 型付きDTO、描画/metadata分離、AがPsdOriginと保存情報を保持 | nodes/warningsは変更不可Map | 共通PsdNode・ImportWarning・結果型へ移行し、未知の元属性も失わず保持 |
| Python | 3.11系、正確な版と全依存をBで固定 | 3.12.14。pyprojectも3.12のみ許可、lockと実モデルは3.12検証済み | 別venvで3.11対応を検証。既存lockをそのまま対応済みと扱わない |
| サービス寿命 | Bが実装、Aが起動時startと終了時close。1アプリ1サービス | closeは再起動可能な停止、disposeがreader executorも解放 | 最終終了と再起動の契約を整理。closeで全資源を解放できる公開境界へ変更 |
| 設定・ログ | macOSのApplication Support/ImageEditor以下 | startにPython・作業dir・manifestを渡す。stderrを読み捨て | Aが設定読込、Bへ値を注入。Bの安全なログ出力と保存先の受渡しを接続 |
| 上限・HTTP | BのAPI v1、寸法・容量・キュー・タイムアウトを採用 | 大半は同じ設計値で実装・検証済み | 各値を照合。サーバーの処理期限とクライアントの通信期限を区別して維持 |

JavaFX・Jackson・JUnitの正確な版、contractの全メソッド署名はPDFだけでは固定できません。JavaFXは21系、Jacksonは2.18系、JUnitは5系という指定から任意のpatch版を確定せず、Aの既存Version Catalogがあればそれを採用します。

## 3. Aの回答で具体化された分担

### AG-02・03：AI

AがdocumentId、layerId、contentRevision、aiSessionId、最新requestIdを所有し、UI反映直前にも再検証します。BはserviceInstanceId/imageId/requestIdによる旧結果の抑制を維持します。

画素変更・Undo/Redoは新しいcontentRevisionを発行します。位置・回転・拡縮・opacity等だけの変更では登録画像を維持し、Aが最新transformをoverlayと確定Layerへ適用します。登録中にセッションが失効した場合のimageId解放もAの責務です。Bはその解放を安全に受け付けます。

Version 1の対象はRasterLayerです。画面座標の逆変換とfloor、元alphaとマスクの1回だけの乗算、新規Layerの追加とUndo/RedoはAが担当します。BにDocumentやLayer更新を追加しません。

### AG-04：幾何編集

次の操作を分離します。

- Layer単体のResize/Crop/回転：Bが1枚の画像を処理し、AがLayerへ反映。
- 文書Resize：Aが全Layerを一括制御し、Raster/Drawing画素のResizeだけBを利用。TextはAが編集可能性を維持。
- Canvas Resize：AがCanvas寸法・原点を変更し、画素を再サンプリングしない。
- Canvas Crop：AがCanvas寸法と全Layer位置を変更。範囲外Layerも保持し、描画時にclip。
- 文書全体90度回転：AがCanvas寸法と全Layerの位置・回転プロパティを変更。Bの画素回転を全Layerへ重ねて適用しない。

文書全体操作はAが全結果を準備してから1 Commandとして確定します。BへCanvas Cropによる破壊的画素削除やTextの強制ラスタ化を追加する計画にはしません。

### AG-05～07：PSD・プレビュー・履歴

PSDは新しいDocumentとして開きます。Bは元階層・元属性・置換範囲・警告を返し、Aは警告確認後にDocumentを公開します。AがPsdOrigin(importId,nodeId)とPsdImportMetadataを保持し、.jimgのimports/{importId}.jsonへ保存します。Bは.jimg保存を実装しません。

RASTER/REPLACEMENT_RASTERだけを描画し、metadata専用ノードにはassetを付けません。描画順は兄弟ごとの下から上の順序からAが平坦化します。代替合成済みopacity=1とeffectiveVisibleを利用し、effectiveOpacityを重ね掛けしません。

プレビューはAがダイアログ開始画像とpreviewRequestIdを管理します。Bの内部値域と計算式は維持し、UIの-100～100変換、縮小時の半径調整、原寸確定はAが制御します。

差分Tile、256MiB履歴予算、dirty、Redoロックと失敗時の状態保持はAが担当します。Bは決定性・変更範囲・algorithmVersionを返します。今回の移行で演算結果が変わらない場合はアルゴリズム版を不要に変更しません。将来式や丸めを変更する場合のRedo版不一致時の扱いは、contractレビューで確認します。

## 4. 推奨する実施順と完了条件

| 順序 | 作業 | 主担当 | 完了条件 |
| --- | --- | --- | --- |
| P0 | 現在の安定版と測定結果を保存し、A回答の差分を記録 | B | 現コードの再現点、資料、差分表が参照できる。本計画で実施 |
| P1 | common-contractとビルド境界の具体案を作りレビュー | Bが案、A+Bで確認 | AのStubとBの最小実装が同じ署名でコンパイル可能。版と所有者を記録 |
| P2a | Java 21 / Gradleへ移行 | B、共有buildはAとレビュー | Wrapper 8.10.2、モジュール依存、Catalogが固定され、既存回帰検証がGradleから実行可能 |
| P2b | Python 3.11の互換性検証とlock作成 | B | 別venvでcore/full導入、pip check、通常試験、PSD、実MPS/CPU推論とfallbackが成功 |
| P3 | 処理contract・取消ハンドル・checked例外を実装 | B | 成功・失敗・待機取消・実行中取消・BUSYを同じcontractで検証。画素の回帰試験通過 |
| P4 | AI/PSD公開型、Jackson、サービス寿命を移行 | B | AI/PSD contract試験、上限・異常応答試験、最終closeの資源解放、実サービス結合が通過 |
| P5 | 初回のA/B結合 | 共同 | 共通contract、Stub成功/失敗/取消、基本処理1件、サービス起動/終了をAから実行 |
| P6 | AI・履歴・競合・障害のMUST受入 | 共同 | I01～I04をA UIで通し、失敗/取消でDocument・dirty・Historyを維持 |
| P7 | PSDのSHOULD受入と配布環境の評価 | Bが素材・処理、Aが表示・保存 | I05、実PSD評価、.jimg metadata復元、評価PC再現、性能記録が揃う |

P1の具体化後、P2aとP2bは別作業として進められます。Pythonの調整中も、AはStubで進められ、Bの既存3.12環境は比較用として保持します。初回結合を実モデル対応やPSD完成まで待たせません。

### P1：最初に作る具体的な成果物

1. 共通APIのJavaソース案：画像処理のRequest/Result/Parameters、AI登録・点・候補・結果、型付きPSDノード・資産・警告、ErrorCode、AsyncOperation。
2. 必要なinterfaceの一覧：画像処理、AI、PSD。Aは具体実装に直接依存せずCoordinatorからinterfaceを利用し、アプリ初期化箇所でB実装を注入。
3. 例外と取消の規約：3種のchecked例外にcode・requestId・causeを保持。非同期の失敗をstageの例外完了で返し、取消と通常失敗をAが判別可能にする。
4. Stub：固定画像成功、型付き失敗、取消、遅延して返る旧結果を再現。実モデル不要でAが使用可能。
5. モジュール依存図・Version Catalog案・最小の契約試験。

型名・メソッド署名・AsyncOperationとErrorCodeの配置は、現HelperClientのネスト型やProcessing型をそのまま公開の正本にせず、上記の案で具体化します。画像は独立した所有物として返し、recordに入れただけでBufferedImage自体が不変になるとは扱いません。リスト等は防御的にコピーします。

cancel()は取消要求の受付を表し、モデル演算の即時中断を保証しない方針とします。取消後のstageの完了形、複数回取消、完了後取消の戻り値、待機枠解放を試験で固定します。呼出し元からstageを強制完了できない構成にします。

### P2b：Pythonの差異への対応

Aの指定に合わせるため、まず3.11系での成立性を検証します。現在の3.12用lockにあるNumPy・PyTorch等が3.11用wheelを提供するか、採用済みMobileSAM/timmとの組合せが動くかを確認します。対応する具体版が必要なら、変更理由・上流の対応範囲・再試験結果を依存差分として提示します。

同じlockを無条件に転用したり、pyprojectのrequires-pythonだけを広げて対応完了とはしません。候補lock・検証環境を分離し、成立後にpyproject、整形設定、CI、起動検証ツール、READMEをまとめて更新します。モデルの重みとSHA-256、HTTP API v1は維持する方針です。

3.11での採用構成が成立しない、または品質・性能・配布に大きな不利益がある場合は、3.12維持案と根拠をAへ提示します。この分岐は技術検証後の判断とし、現在の時点で3.11対応済みとも3.12継続合意済みとも扱いません。

### P3～4：実装移行の注意点

- まず既存処理系へ薄い共通contractアダプターを付け、試験を維持してから内部型を整理します。二重の公開APIを長期に残しません。
- Javaの移動に合わせてCLI、性能測定ツール、build/test/coverage、CI、使用手順のパスを更新します。性能ベンチマークは通常CIでは自動実行しません。
- Jackson移行でも、容量上限、非有限値、必須項目、整数の範囲、重複ID、ZIP traversal、二値マスク等の検証を落としません。HTTP APIは変更しません。
- PSDの型付きDTO化はJava側の共通contract移行として扱い、PythonのZIP schemaVersion=1を理由なく上げません。元属性の保存と未知フィールドの方針もレビュー対象とします。
- サービス再起動用のstop/restartと最終closeを分け、close後にreader executorや所有子プロセスが残らないようにします。再起動で旧imageIdと未完了要求を失効させます。
- 設定とログの保存場所はAの指定へ合わせ、Token・画像・クリック列をログに出さず、Aから渡された設定値を使います。起動・終了の待機をJavaFX UIスレッドで行わせません。
- healthとAI準備を分け、AI準備60秒の監視と状態通知の担当境界を明記します。AI失敗でも通常編集とPSDを継続させます。

## 5. 初回レビューで確認する残りの項目

従来のAG-01～09全体を再質問する必要はありません。以下へ絞って確認します。

| ID | 確認内容 | B側の進め方 |
| --- | --- | --- |
| C1 | A側に既存のcommon-contract、Gradle構成、Version Catalog、Stubの実コードがあるか。採用するbranch/commitはどれか | あればそれへ合わせる。なければBが最小案を作り共有buildとcontractをレビュー |
| C2 | 共通API全署名、ErrorCode全一覧、非同期取消完了形、共有補助型の配置 | P1のソース案と試験で具体的に確認 |
| C3 | Python 3.11系への移行検証結果と採用する正確な版・lock | Bが検証結果を提示。必要な依存差分または3.12維持案を確認 |
| C4 | LocalServiceManagerの最終close、再起動、AI準備待ち、ログ保存先の受渡し | Bが明確なライフサイクル案を提示し、Aの起動コードと接続 |
| C5 | 初回結合の実施日と、Aの最小画面/Coordinatorが使用可能な時点 | 共通contract・Stub・基本処理・起動終了の小さな結合から開始 |

毎週月曜16:00～、問題があれば翌火曜12:45～14:15の授業内で相談、という記載は候補です。初回の日付も双方の予定も未確定のため、2026年10月19日から開始済みとは扱いません。予定登録や定期通知も行いません。

## 6. 結合の分担と判定

| ケース | Bが用意するもの | Aが実装・確認するもの | 合格条件 |
| --- | --- | --- | --- |
| 最初の接続 | 成功/失敗/取消Stub、基本処理、起動終了API | Coordinatorと最小操作入口 | 共通型でビルドし、UIスレッドを止めず成功・失敗・取消を扱える |
| I01 基本処理 | 原画像・期待画素・処理パラメータ・algorithmVersion | 表示・Command・Undo/Redo・保存 | 画素・寸法・dirtyが正しく復元される |
| I02 AI | 実モデル、3候補、正負クリック、原寸二値mask | 候補切替・座標・alpha・新Layer・履歴 | 任意候補を確定可能。元画像不変、Redoで再推論しない |
| I03 競合 | 遅延・順序逆転・取消・サービス再起動の再現 | 対象/世代/session/requestId再検証 | 古い結果がDocumentにもoverlayにも適用されない |
| I04 障害 | モデルなし・BUSY・TIMEOUT・不正応答等 | ErrorCode別案内と編集継続 | 失敗時にDocument/Historyを維持し、通常編集・保存が可能 |
| I05 PSD | 通常/代替/metadata混在DTO、警告、入力と期待表示 | Warning確認、新Document、PsdOrigin、.jimg保存 | Cancelで未反映、二重描画なし、保存後も元情報を保持 |

Bが入力・期待結果・性能と環境情報を用意し、AがDocument/Layerを含むシナリオと結果記録を担当、最終判定は共同とします。今回の回答により、この全体試験の取りまとめが未担当という問題は解消方向です。

## 7. 着手単位とGit運用

推奨する変更単位は、(1)contract案とStub、(2)Java/Gradle配置、(3)Python互換性とlock、(4)Processor接続、(5)AI接続と寿命管理、(6)PSD/Jackson接続、(7)A/B受入、です。最初に全ソースを一括移動して、長期間ビルド不能な状態にはしません。

A提案の運用はmain=安定版、integration=共同結合、feature/a-*・feature/b-*=短期作業、原則1機能1PRです。これは今後の運用案として採用方向とし、これまでのB単独のmainへの統合と区別します。共有contract/build/依存/API/DTOは相互レビュー対象、相手所有モジュールの変更は事前相談です。ユーザーからの既存のmain統合許可を取り消すものではありません。

移行後は現在の回帰検証・HTTP/PSD契約検証・実モデル結合を維持し、行95%以上・分岐85%以上の基準を新モジュールにも適用します。JUnit移行でテストの数え方が変わっても、既存の独立した画素期待値や異常応答の検証を削りません。API移行と性能改善を同じ変更へ混ぜず、性能値の再測定は新実行環境で行います。

## 8. 後回しにするもの

新しいフィルター、PSD完全互換、AI自動精度改善、UI/History/.jimgのB側重複実装は追加しません。実PSDの多様な互換性評価、数時間の連続運用、MPSを含む資源計測、別評価PCでの再現は継続課題ですが、共通contractと最初のMUST結合を優先します。
