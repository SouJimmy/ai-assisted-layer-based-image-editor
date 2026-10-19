# 共通contract設計書

更新日：2026年10月19日 / 対象：Version 1 / 担当Bによる実装・相互レビュー用設計。

## 1. 適用範囲と位置づけ

[担当A回答](../references/合意事項_担当A回答.pdf)のAG-01～09と[担当A詳細設計](../references/プログラミング言語_詳細設計_担当A.pdf)を基準に、公開APIを具体化します。Java 21、record、AsyncOperation、checked例外、JavaFX非依存はA回答に沿う方針です。本書で追加したメソッド署名、共有補助型の配置、取消の競合規則はBの具体案であり、双方の採用確認前に合意済みとは扱いません。

本書が改訂後の共通型の設計正本、[担当B詳細設計](担当B_詳細設計書.md)が処理式・HTTP・資源制限の設計正本です。本書の共通APIとB接続層は実装済みで、Processing/HelperClientの旧型はinternalの演算・通信境界に閉じ込めています。詳細は[移行実装記録](../contract-migration.md)を参照してください。Aの画面との結合と最終相互レビューは未完了です。

## 2. モジュールと型配置

ルートパッケージは`jp.ac.example.imageeditor`。以下の表はその下のパッケージを示します。

| module / package | 公開型 |
| --- | --- |
| common-contract / contract.common | AsyncOperation、ErrorCode、BackendException、CancellationToken、ProgressListener、ServiceError |
| common-contract / contract.processing | ImageProcessor、ProcessingRequest、ProcessingResult、ProcessingContext、ProcessingOperation、ProcessingParametersと各record、PixelRect、ProcessingException |
| common-contract / contract.ai | AiSegmentationService、RegisteredImage、AiRequest、PromptPoint、PromptLabel、AiMaskResult、MaskCandidate、DeviceKind、AiServiceException |
| common-contract / contract.psd | PsdImportService、PsdImportResult、PsdNode、RasterAsset、ImportDisposition、ImportWarning、WarningCode、PsdImportException、MetadataValue |
| processing-backend / processing | DefaultImageProcessor、ProcessingExecutor、内部ImageFilter・画素演算 |
| processing-backend / integration.ai | HttpAiSegmentationService |
| processing-backend / integration.psd | HttpPsdImportService、PsdArchiveReader |
| processing-backend / integration.runtime | LocalServiceManager、ServiceConfiguration、ServiceStatus、ServiceState、ServiceLifecycleException |

contract.commonは3領域で共有する補助型のために本書で追加する配置案です。common-contractはjava.baseとjava.desktopのみへ依存し、JavaFX、AのDocument/Layer、Jackson、B実装へ依存しません。Jacksonはprocessing-backendの通信境界で使用します。MetadataValueを設けることで、Aの保存処理へJackson固有型を強制しません。

editor-appはcommon-contractを通してCoordinatorを実装します。B具象実装の生成・ServiceManager設定はアプリの構成箇所へ集約し、Controllerから直接newしません。共通contractの版はBのalgorithmVersion、HTTP API版、PSDスキーマ版と区別します。

## 3. 共通の非同期・例外・所有権

### 非同期ハンドル

```java
public interface AsyncOperation<T> {
    CompletionStage<T> stage();
    boolean cancel();
}
```

- stage()は同一操作の結果を返す読取用ビューです。toCompletableFuture()で得たFutureを呼出し元が完了・取消しても内部操作へ影響させません。取消はcancel()だけから伝えます。
- 状態はPENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED。開始・完了・取消は同じ終端判定で競合を解決し、終端は1回だけです。
- cancel()は未完了操作をCANCELLEDへ遷移させた最初の呼出しだけtrue、二度目以降と成功/失敗後はfalseを返します。要求を受理した時点でstageを領域別例外(code=CANCELLED)として例外完了させます。
- cancel受付後に演算が成功・失敗しても結果を公開しません。モデル演算の即時停止は保証せず、実処理終了までPython側の実行枠を占有させます。
- キュー待ちの取消はジョブを除去して枠を解放します。画像処理は取消トークンとinterruptを行単位で確認。HTTP待ちはinterrupt/要求取消を伝播します。
- 同期processでの取消もProcessingException(CANCELLED)です。CompletableFuture.cancelによるCancellationExceptionを公開規約とはせず、既存Futureから共通ハンドルへ変換する際に正規化します。
- コールバックと進捗の実行スレッドはBのバックグラウンド側です。JavaFXへの配送、最新状態の再検証はAが行います。コールバックはワーカーを長時間ブロックしません。
- 新規受付後のキュー満杯、引数不正、終了済みサービス等は、非同期APIでは失敗済みハンドルを返し、操作例外を同期throwしません。record等の生成時の構造不正はIllegalArgumentExceptionで拒否可能とします。

登録操作の取消後にPOST /imageの成功応答を受信できた場合、BはimageIdを公開せず解放を試みます。応答を受け取れずIDが不明の場合はサーバーのLRU/TTLで回収します。登録完了が取消より先に勝った場合や、Aのセッションだけが失効した場合はAが返却imageIdを解放します。

### checked例外

```java
public abstract class BackendException extends Exception {
    public abstract ErrorCode code();
    public abstract Optional<String> requestId();
    public abstract OptionalInt httpStatus();
    public abstract boolean retryable();
    // 原因はException.getCause()で参照する。
}
public final class ProcessingException extends BackendException { /* ... */ }
public final class AiServiceException extends BackendException { /* ... */ }
public final class PsdImportException extends BackendException { /* ... */ }
```

同期処理はProcessingExceptionを宣言します。非同期の失敗は対応するchecked例外をstageの原因として保持し、CompletionException/ExecutionExceptionの通常のラップを解いた後にAがcodeで判断します。公開メッセージは診断補助であり、Aはその文字列を解析しません。未知HTTP codeはINVALID_RESPONSEに正規化し、元HTTP status等は診断情報へ保持します。retryable=trueでも自動再送を意味しません。

ErrorCodeは次の固定enumです。

```text
INVALID_ARGUMENT, RESOURCE_LIMIT, CANCELLED, BUSY,
AUTH_FAILED, API_VERSION_UNSUPPORTED, NO_RESULT, INVALID_IMAGE,
UNSUPPORTED_FORMAT, IMAGE_NOT_FOUND, REVISION_MISMATCH,
MODEL_NOT_READY, MODEL_INIT_FAILED, INFERENCE_FAILED, PSD_PARSE_FAILED,
TIMEOUT, SERVICE_UNAVAILABLE, INVALID_RESPONSE, INTERNAL_ERROR
```

ServiceErrorは準備状態に付随する不変DTOで、ErrorCode code、String message、Optional<String> requestId、boolean retryableを持ちます。Token、画像、クリック列、任意のThrowableを含めません。通常画素演算のValidation/取消/BUSYはProcessingException、AIはAiServiceException、PSD転送・ZIP検証はPsdImportExceptionへ変換します。

### 画像とコレクションの所有権

入出力は独立したTYPE_INT_ARGB_PRE。AIマスクだけはTYPE_BYTE_GRAYで、原寸・0/255のRasterサンプルを保持します。recordは画像の可変性をなくさないため、Aは入力を処理中に変更せず、Bは入力を変更せず、出力と入力・別候補・内部キャッシュのRasterを共有しません。Aへ返した後にBは出力を保持・変更しません。

List/Mapは防御的コピーし、入れ子のmetadataも不変値へ変換します。画像アクセサーの呼出しごとにコピーせず、所有権を渡す時点で独立性を保証します。Bが完了と取消の競合で破棄した画像は内部参照を解放します。

## 4. 画像処理

```java
public interface ImageProcessor {
    ProcessingResult process(ProcessingRequest request, ProcessingContext context)
        throws ProcessingException;
    AsyncOperation<ProcessingResult> submit(
        ProcessingRequest request, ProgressListener progress);
}
public record ProcessingRequest(
    BufferedImage source, ProcessingOperation operation,
    ProcessingParameters parameters) {}
public record ProcessingResult(
    BufferedImage image, PixelRect sourceChangedBounds,
    PixelRect outputChangedBounds, boolean geometryChanged,
    boolean deterministic, String algorithmVersion,
    ProcessingOperation operation, ProcessingParameters parameters) {}
public record ProcessingContext(
    CancellationToken cancellationToken, ProgressListener progressListener) {}
public interface CancellationToken { boolean isCancellationRequested(); }
public interface ProgressListener { void onProgress(double fraction); }
public record PixelRect(int x, int y, int width, int height) {}
```

ProcessingParametersはsealed interfaceとし、NoParameters、CropParameters(PixelRect rect)、ResizeParameters(int width,int height)、ColorAdjustParameters(int brightness,double contrast,double saturation)、BlurParameters(int radius)、MosaicParameters(int blockSize)だけを許可します。ProcessingOperationはB詳細設計3章の12操作です。

processは呼出しスレッド上の同期演算でありUIスレッドから直接呼びません。submitは1ワーカー・待機2件の実行器へ委譲します。同一のDefaultImageProcessorのprocessとsubmitは同じ純粋演算を使い、キュー状態を演算へ混ぜません。DefaultImageProcessorはImageProcessorとAutoCloseableを実装し、closeで新規受付を止め、未完了操作をCANCELLEDにして所有実行器を停止します。閉鎖はアプリの構成箇所で所有する具体実装に対して行い、AのCoordinatorは共通ImageProcessorだけを保持します。

入力/出力寸法、operationとparametersの整合、NaN等は演算前に検証。失敗・取消で入力や部分結果を公開しません。Progressは0～1、単調非減少、最大10回/秒を基本とし、成功時は最後に1.0を通知します。進捗通知先の例外は処理結果の成功/失敗判定を変更せず診断対象とします。

変更候補範囲は全面でもよく、Aが実画素差のあるTileだけを保存します。geometryChangedは入力と出力の幅/高さが異なる場合だけtrueです。正方形の回転や画像全体のCropではfalseになり得ます。操作内容はoperation/parametersで判定します。演算の式・丸めが同じならパッケージやAPI移行だけでalgorithmVersionを変えません。

## 5. AI

```java
public interface AiSegmentationService {
    AsyncOperation<RegisteredImage> registerImage(
        BufferedImage snapshot, String sourceRevision);
    AsyncOperation<AiMaskResult> segment(AiRequest request);
    AsyncOperation<Void> releaseImage(RegisteredImage image);
    void invalidateRequests(RegisteredImage image);
}
public record RegisteredImage(String imageId, String sourceRevision,
    int width, int height, String serviceInstanceId) {}
public record AiRequest(RegisteredImage image, UUID requestId,
    List<PromptPoint> points) {}
public record PromptPoint(int x, int y, PromptLabel label) {}
public enum PromptLabel { NEGATIVE, POSITIVE }
public enum DeviceKind { CPU, MPS }
public record MaskCandidate(String candidateId, BufferedImage mask, double score) {}
public record AiMaskResult(RegisteredImage image, UUID requestId,
    List<MaskCandidate> candidates, List<PromptPoint> sourcePoints,
    DeviceKind deviceUsed) {}
```

RegisteredImageを要求・解放の入口に含め、異なるserviceInstanceIdから偶然同じimageIdが返った場合も混同しません。各非同期操作は受付時のserviceInstanceIdと内部起動世代を固定し、送信直前・応答公開直前にも現世代と照合します。登録要求も対象とし、再起動後に旧サービスの登録結果を公開しません。HTTP送信時は既存imageId/sourceRevision/requestId/pointsへ変換し、HTTP schemaは変えません。JavaのPromptLabelは送信時にNEGATIVE=0/POSITIVE=1へ変換し、enumのordinalに依存しません。

sourceRevisionはAがdocumentId + ":" + contentRevisionで生成します。Bは内部を解析しません。pointsは順序を保った防御的コピーで、1～64個・正点を1個以上必要とします。deviceUsedはcpu/mpsのみ受理します。候補は1～3、score有限、maskは原寸二値、IDは候補内で一意です。sourcePointsは応答で追加する項目ではなく、検証済み要求からコピーします。

invalidateRequestsは最新要求の世代を失効させ、該当する未完了要求をCANCELLEDにします。画像と埋め込みの解放は行いません。完了済み候補をAから回収できないため、AはUI反映前にもsession/requestIdを確認します。releaseImageは先に要求を失効させ、その後削除を送ります。解放を取消した場合も要求失効を巻き戻しません。

現serviceInstanceIdと一致しない要求は送信せずAiServiceException(IMAGE_NOT_FOUND)とし、Aへ再登録を促します。旧instanceのreleaseImageは現在のサービスへDELETEを送らず、既に解放済みとして成功します。同じinstanceで未知IDのDELETEも204成功です。明示cancel・新要求による旧要求の失効は、通信の成否とは別にローカルで結果公開を抑止します。

## 6. PSD

```java
public interface PsdImportService {
    AsyncOperation<PsdImportResult> importPsd(Path file);
}
public record PsdImportResult(int schemaVersion, int canvasWidth, int canvasHeight,
    List<PsdNode> nodes, Map<String, RasterAsset> rasterAssets,
    List<ImportWarning> warnings) {}
public record RasterAsset(String assetId, BufferedImage image) {}
public record PsdNode(String id, Optional<String> parentId, int siblingIndex,
    String name, String kind, int x, int y, int width, int height,
    boolean selfVisible, boolean effectiveVisible, double opacity,
    double effectiveOpacity, String blendMode, Optional<String> assetId,
    ImportDisposition importDisposition, Map<String, MetadataValue> originalMetadata) {}
public enum ImportDisposition {
    RASTER, REPLACEMENT_RASTER, GROUP_METADATA, METADATA_ONLY, EMPTY_METADATA
}
public enum WarningCode { RASTERIZED, FLATTENED, EMPTY_LAYER, EXTRACTION_FAILED, UNKNOWN }
public record ImportWarning(WarningCode code, String rawCode,
    Optional<String> nodeId, String message, ImportDisposition disposition,
    String sourceKind, String unsupportedFeature) {}
```

MetadataValueは以下を許可するsealed値型です。

```text
MetadataNull（単一値）
MetadataBoolean(boolean value)
MetadataString(String value)
MetadataNumber(BigDecimal value)
MetadataArray(List<MetadataValue> values)
MetadataObject(Map<String, MetadataValue> values)
```

配列・Mapを再帰的に防御的コピーし、Mapキーや値にJavaのnullは入れません。JSONのnullはMetadataNullで保持します。数値はBigDecimal相当で精度を落とさず保持し、JsonNode等をcontractへ漏らしません。未知の元属性もこの値型で保持します。HTTPの未知ErrorCodeは失敗としますが、表示用の未知WarningCodeはUNKNOWN + rawCodeとして保持し、内容を欠落させません。

schemaVersion=1。Java recordのOptionalはJava内の表現で、Jacksonによるrecordの丸ごと自動直列化をHTTP/.jimgの仕様にしません。通信DTOから明示的に変換し、parentIdはnull、省略されたassetIdはOptional.emptyへ正規化します。描画用assetIdは必須、metadata専用ノードは空。Pythonが現在metadataノードのassetIdをnullとして出す場合も、省略と同義として受理します。

A回答の500ノード上限を安全側に解釈し、返却nodesも補助的な代替ノードを含め最大500とします。入力が上限内でも、元情報を保持したまま代替ノードを追加すると上限を超える場合はRESOURCE_LIMITとし、黙って元ノードを捨てません。この数え方は相互レビューで確認します。Java/Pythonの双方をこの上限へ修正し、501件拒否の回帰試験を追加しました。合成ノードは元ノードと異なるIDを持ち、元ノードはMETADATA_ONLYです。各parent内のsiblingIndexを保持して深さ優先に下から上へたどると描画順が得られる構成にし、Aは親のない置換ノードも含めて同じ規則で平坦化します。

rasterAssetsのキーとRasterAsset.assetIdは一致が必要です。描画nodeと資産の寸法一致、ID・親・兄弟順の一意性、親循環、opacityの範囲、資産未参照、容量上限を検証してから結果を公開します。元階層と描画用置換の情報を保ち、AがimportIdを発行してPsdOrigin・PsdImportMetadataへ登録します。型付きDTO化だけではPythonのschemaVersionを上げません。

## 7. サービスの設定・寿命管理

以下はBのintegration.runtimeに置く公開設計です。Aのアプリ構成箇所で所有し、ControllerへはAI/PSDの共通interfaceだけを渡します。

```java
public final class LocalServiceManager implements AutoCloseable {
    AsyncOperation<ServiceStatus> start(ServiceConfiguration configuration);
    AsyncOperation<ServiceStatus> restart();
    AiSegmentationService aiService();
    PsdImportService psdService();
    ServiceStatus status();
    void close();
}
```

ServiceConfigurationはpythonExecutable、serviceDirectory、modelManifest、logDirectoryの絶対Pathを持ちます。AがApplication Support/ImageEditor/config.jsonを読み、既定値を解決して渡します。Bは設定ファイルを書き換えません。正規化済みのパスをProcessBuilderへ渡し、Tokenを保存しません。

ServiceStatusはServiceState、Optional<String> serviceInstanceId、boolean aiReady、boolean psdReady、Optional<DeviceKind> device、Optional<ServiceError> aiError、Optional<ServiceError> lifecycleErrorを持つ不変スナップショットです。ServiceStateはSTOPPED/STARTING/RUNNING/FAILED/STOPPING/CLOSED。プロセスの状態とAI準備の状態を分けます。ServiceLifecycleExceptionはcheckedなBackendExceptionのB側サブクラスとし、起動自体の失敗とAIだけの準備失敗を区別します。この追加例外型とruntime公開署名も相互レビュー対象です。

- startは同一設定のRUNNINGなら成功済みハンドルを返し、STARTING/STOPPING中の再呼出しはBUSYとします。未完了startのcancelは未公開の子を停止し、遅れてRUNNINGを公開しません。異なる設定での二重startはINVALID_ARGUMENT、CLOSEDはSERVICE_UNAVAILABLEで失敗完了。
- aiService()/psdService()は安定したfacadeを返し、再起動でinstanceを切り替えます。RUNNING前やCLOSEDでの要求は失敗完了します。古いRegisteredImageは新instanceへ引き継ぎません。
- startの成功は、10秒以内の起動通知検証と認証付きhealth成功までです。healthは1回2秒、モデルimport時の一時的な期限超過に限り全体10秒の有界照会を行います。AI準備を60秒同期的に待ちません。AI監視は通知を検証した時点から60秒、500ms間隔でhealthを照会しstatusへ反映します。準備監視TIMEOUTだけで生きているPSDサービスを停止しません。その後も明示的な状態更新で遅れて準備できたAIを利用できます。
- AI初期化失敗はaiErrorへ保持し、psdReady=trueならRUNNINGを維持します。子終了や認証・instance不一致はFAILEDとし未完了要求を失敗させます。無制限の自動再起動は行いません。
- restartは未完了要求・旧IDを失効させ、所有子を停止して新instanceを起動します。restart自体の取消はその起動世代を失効させ、後からRUNNINGを公開せず、新たに起動した子も回収します。停止中の新規処理要求はSERVICE_UNAVAILABLE、同時restartはBUSYとします。
- closeは最終終了で冪等です。受付停止、要求のCANCELLED完了、所有子へのdestroy、5秒猶予後のdestroyForcibly、reader/HTTP/executorの停止と参照解放を行います。強制終了後の終了確認は追加5秒を上限とし、失敗を安全な診断ログへ残します。CLOSEDから再起動しません。
- start/restartの待機とcloseはUIスレッドで行いません。Aは終了をバックグラウンドで実行し、完了を待ってUIを閉じます。Aの未保存確認はcloseより前です。
- 標準出力は上限8KiBの通知1行を検証し、その後も読み続けます。stderrはToken・画像・クリック列等を残さない構造化診断へ変換し、保存先の作成/書込失敗で通常編集を停止させません。生stderrを無条件で永続化しません。ファイルは5MiB×3世代をBの設計値とします。

## 8. レビューと実装の完了条件

A回答にない具体署名・補助型・runtime規約は、本書を用いて相互確認します。既存Aコードがある場合は重複する共通型を作らず本書を更新します。Python 3.11.16とlock、Jackson 2.18.12等をB側で検証し固定しました。Aとの最終相互レビュー・アプリ結合は残っています。

| ID | 検証 |
| --- | --- |
| C01 | Java 21でcontract単体とAのStub/Bアダプターがビルドでき、JavaFX/Jackson/B具象型の依存がcontractへ漏れない |
| C02 | 引数不正・BUSY・処理失敗は非同期ハンドルのchecked例外として取得できる |
| C03 | 待機取消で枠解放、実行中取消で結果未公開、二重取消/完了競合の戻り値、stageの外部変更が内部へ影響しない |
| C04 | 入力と出力、候補同士の画像独立性、Listとmetadataの深い防御的コピー |
| C05 | 旧serviceInstanceIdを新サービスへ送らず、取消後登録と失効セッションのimageIdを回収できる |
| C06 | 型付きPSDのnull/省略、補助ノード込み500件と501件拒否、未知warning/元属性、親と描画順を検証 |
| C07 | start/AI準備/再起動/最終closeを分離し、準備失敗でもPSDが残り、close後にreader等が残らない |
| C08 | JUnit移行後も既存の独立画素計算・HTTP異常系・PSD境界・実モデル結合を維持 |
