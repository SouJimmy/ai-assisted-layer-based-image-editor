# 共通contract具体案の確認事項

担当Bから担当Aへ / 2026年10月19日 / Version 1

詳細設計とAG-01～09の回答をありがとうございます。回答に合わせて[担当B詳細設計](../design/担当B_詳細設計書.md)を改訂し、[共通contract設計書](../design/共通contract設計書.md)に型・署名・取消等を具体化しました。Java 21、モジュール構成、A/Bの責務、UI/履歴/PSD保存方針は回答に沿っています。

以下は回答にない部分をBで具体化した案です。共通コードへ反映する前に、この案で接続できるかを確認したいです。既存のcommon-contract、Gradle/Catalog、Stubがある場合は、参照するbranch/commitを教えてください。重複実装を作らず合わせます。

| 項目 | Bの具体案 | 確認をお願いします |
| --- | --- | --- |
| 公開型 | contract.processing / ai / psdに領域型、共有補助型はcontract.common | 既存Aコードと名前・配置・全署名を揃えられるか |
| 画像処理 | 同期processと非同期submitをImageProcessorへ集約。Resultはoperation/parameters等を保持 | Coordinatorから呼ぶ入口と合うか |
| 非同期取消 | 最初の取消だけtrue、stageは領域別checked例外(CANCELLED)で完了。演算完了後の取消はfalse | Aの取消・エラー表示がこの規約に対応できるか |
| AI | AiRequestとreleaseImageにRegisteredImageを使い、serviceInstanceIdを明示。invalidateRequestsは解放せず旧要求だけ失効 | Aが保持する登録情報と接続できるか |
| PSD | 型付きPsdNode/ImportWarning、元属性はJackson非依存のMetadataValue、未知warningは内容を保持 | PsdImportMetadata/.jimg保存へ欠落なく変換できるか |
| ノード上限 | 補助ノードを含む返却数を500までとする保守的解釈 | AG-09の500件が元PSDだけか返却数も含むか。元500＋補助1を許す場合は双方で上限を明記したい |
| サービス寿命 | start成功はHTTP確認まで、AI準備は別監視。restartと最終closeを分離し、closeで全資源を解放 | Aの起動・終了処理と合うか。起動例外ServiceLifecycleExceptionはBのruntime型として追加 |
| Python | 指定の3.11系を別venvで検証し、具体版とlockをBで提示 | 検証不成立時は根拠を示して3.12維持案を相談する。現時点では3.11対応は未完了 |

DTOのrecord・Optional等はJava内の表現です。HTTP API v1・PSD ZIP schemaVersion=1は維持し、Java recordの直列化結果をそのまま通信仕様へ置き換えません。画像の独立所有権とList等の防御的コピーも規約へ含めています。

最初の結合は、共通contractとStubの成功・失敗・取消、基本処理1件、サービス起動・終了までを対象にしたいです。定例の月曜16:00～と、問題がある場合の火曜12:45～14:15の授業内相談は候補として理解しています。初回の実施日とA側の最小接続部分が使える時点を確認したいです。

## 回答欄

- 既存コードのbranch/commit：
- 公開型・署名の変更希望：
- 取消・サービス寿命の変更希望：
- PSD元属性とノード上限の確認：
- 初回結合の候補日：
