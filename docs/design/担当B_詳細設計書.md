# 画像編集ツール 担当B 詳細設計書

2026年10月19日受領のA資料との差分は[移行・結合計画](../coordination/担当A回答を踏まえた移行・結合計画.md)で管理しています。本書のJava 17・Python 3.12等をAの指定と合意済みの環境とは扱いません。共通contractの具体化後に本書も更新します。

画像処理 AI連携 PSD取り込み 外部処理基盤

作成日 2026年10月13日  /  対象 Version 1

実装済み範囲・B側の暫定API・初期PSD取り込みの制限は[実装状況](../implementation.md)を参照してください。共通境界は担当Aとの合意後に固定します。

### 本書の目的

担当Bが実装する処理系と、担当Aが所有するエディタ本体との受け渡しを具体化する。画像を入力して結果を返す構成とし、担当BからDocument、LayerManager、HistoryManager、JavaFXのControllerを直接変更しない。担当Aは処理結果を検証し、レイヤーや履歴へ一括反映する。

### 設計の基準

既存資料で確定したMobileSAM常駐、imageId単位の埋め込みキャッシュ、最大3候補、MPSからCPUへの1回の再試行、psd-tools、TYPE_INT_ARGB_PRE、非同期実行、256px差分タイルとの連携を維持する。資料内で暫定記述と完了記録が併存する場合は、概要設計20章および技術検証メモ10章の確定差分を優先する。

本文中の「設計値」「提案契約」は本書で具体化した値または仕様を示す。共通contract、依存バージョン、起動構成は担当Aと相互レビューして固定する。既存資料に記載された実測値は参照値であり、本書の実装での達成を意味しない。

### 構成

| 章 | 内容 |
| --- | --- |
| 1～3 | 担当範囲、モジュール、画像処理契約 |
| 4～6 | 幾何変換、色調整、ぼかしとモザイク |
| 7～10 | AI契約、HTTP仕様、モデルとキャッシュ、サービス運用 |
| 11～12 | PSD契約、変換と警告 |
| 13～15 | 非同期と履歴連携、テスト、実装順と合意事項 |
| 16 | 参照資料と要件対応 |

## 1 担当範囲と完了条件

| 対象 | 優先度とBの責務 | Aとの境界 |
| --- | --- | --- |
| FR-05～07 | MUST 切り抜き、90度回転、反転、サイズ変更 | 対象範囲、操作入力、キャンバス変更はA |
| FR-11～14 | MUST 全体ぼかし、全体モザイク、色調整、基本フィルター | 適用対象と確定操作はA |
| FR-15 | SHOULD プレビュー用の純粋処理 | 表示、確定、キャンセルはA |
| FR-29～34 | MUST 登録、推論、最大3候補、エラー変換 | 候補選択、新規レイヤー生成、UndoはA |
| FR-25～27 | SHOULD PSD解析、RGBAと中間DTO、警告 | 内部レイヤー変換と警告表示はA |
| NFR-02～08 | 非同期、安全な失敗、ローカル通信、GUI非依存 | AがUIへ完了通知を反映 |

### 対象外

PSD書き出し、完全なPhotoshop互換、AI画像生成、クラウド送信はVersion 1の対象外。任意範囲モザイク、シャープ、輪郭抽出、バッチ処理等の発展機能はMUST安定後に扱う。通常画像の開く・保存、.jimgのスキーマと実装、描画入力、履歴スタックの所有は担当Aとする。Bは非同期実行の補助を提供できる。

### B単体の完了条件

全MUST Processorの画素結果と入力不変性を確認できる。AIは実機で人物・動物・日用品を分離し、候補切り替えに必要なデータを返せる。モデル未起動、不正応答、キャンセル時にも編集状態を変更しない。共通contractとStubを提供し、担当AのUIから統合できる。PSDはMUSTとは分離して評価する。

### 共同の受入条件

画像を開く → Bの基本処理 → Aの表示と履歴登録 → AI候補の確認 → Aの新規レイヤー生成 → 移動 → Undo/Redo → PNG/JPEG書き出し、を一続きで確認する。最低受入例の「ぼかしまたはモザイク」等だけで実装範囲を狭めず、FRのMUST機能をすべて検証する。

## 2 モジュールと依存関係

| パッケージ | 主要クラス | 責務 |
| --- | --- | --- |
| contract.processing | ProcessingRequest / ProcessingResult / PixelRect | 画像処理入出力と例外契約 |
| processing | ImageProcessor / 各Processor / ImageFilter | GUI非依存の画素演算 |
| contract.ai | AiRequest / AiMaskResult / MaskCandidate | 画像登録と推論の契約 |
| integration.ai | HttpAiSegmentationService / ResponseValidator | HTTP呼出し、応答検証、例外変換 |
| integration.runtime | LocalServiceManager / ProcessingExecutor | 子プロセス管理、実行上限、取消 |
| contract.psd | PsdImportResult / PsdNode / ImportWarning | 階層とRGBAの中間表現 |
| integration.psd | HttpPsdImportService / PsdArchiveReader | PSD転送と返却データの検証 |
| python-service | api / model_runtime / image_cache / psd_importer | FastAPI、MobileSAM、キャッシュ、psd-tools |

### 呼び出し方向

```
A Controller / Task
  -> contract interfaces
     -> B ImageProcessor
     -> B HttpAiSegmentationService -> local Python
     -> B HttpPsdImportService       -> local Python
  <- Result / typed exception
A validates document revision -> Command -> Model -> UI
```

contractにはJavaFXやA所有モデルへの依存を入れない。BufferedImageはJava側の画像境界でのみ用いる。PythonにはJSON、PNG、PSDのバイト列だけを渡す。DTOはrecord相当の不変構造とし、リストを防御的コピーする。BufferedImage自体は可変のため、別途所有権規約を適用する。

### 主要実装の置き換え

ImageProcessorは処理種別ごとにProcessorへ委譲する。ImageFilterはapply(BufferedImage source)を基本とし、FilterProcessorが結果メタデータを付与する。AiSegmentationServiceとPsdImportServiceはインターフェースとし、固定画像を返すStubでUI側を先に結合できるようにする。PythonプロセスとHTTPクライアントは注入可能にして異常系を再現する。

## 3 画像処理の共通契約

```
ProcessingResult process(ProcessingRequest request,
                         ProcessingContext context);
BufferedImage ImageFilter.apply(BufferedImage source);
ProcessingRequest(source, operation, parameters)
ProcessingResult(image, sourceChangedBounds,
                 outputChangedBounds, geometryChanged,
                 deterministic, algorithmVersion)
ProcessingContext(cancellationToken, progressListener)
```

operationはCROP、ROTATE_RIGHT、ROTATE_LEFT、FLIP_H、FLIP_V、RESIZE、COLOR_ADJUST、GRAYSCALE、SEPIA、NEGATIVE、BLUR、MOSAIC。parametersは処理ごとの型で定義し、文字列Mapによる無検証の値受け渡しを避ける。メソッドは同期処理で、非同期化は実行器が担当する。

### 画素表現と所有権

入出力の正本はBufferedImage.TYPE_INT_ARGB_PRE。入力が別形式なら専用コピーへ正規化する。入力、共有配列、キャッシュを変更せず、出力は常に独立した画像とする。getSubimageの共有Rasterを結果として返さない。Aは処理中のスナップショットを書き換えず、Bは返却後の画像を保持・変更しない。

色計算ではgetRGB等で非乗算ARGBとして読み、setRGB等で乗算形式へ戻す。生のARGB_PRE配列に非乗算RGBの式を直接適用しない。alpha=0の画素はRGB=0に正規化する。色管理はsRGBの8bit相当を基準とし、CMYK・RAW・高度なICC対応を含めない。

### 座標と検証

原点は左上、xは右、yは下、単位は原寸の画素。PixelRect(x,y,width,height)は整数の半開区間[x,x+width)×[y,y+height)。ズーム・スクロール・レイヤー位置の逆変換はAが行い、Bは画像内座標だけを受け取る。

設計値：幅・高さは各1～16384、総画素数は最大33,554,432。longの積で検証し、出力寸法にも適用する。UIの指定範囲と上限を一致させる。null、不正な型、範囲外、NaN、InfinityはINVALID_ARGUMENTで処理前に拒否する。上限は性能評価後に両者で調整する。

### パラメータ型の提案

以下を共通contractの具体案とする。型名・例外型・取消APIの採用はAG-01で固定する。enumとparametersの組み合わせを入口で検証し、誤った型はINVALID_ARGUMENTとする。

| operation | parameters | 制約 |
| --- | --- | --- |
| CROP | CropParameters(PixelRect rect) | 画像内の正幅・正高さ |
| RESIZE | ResizeParameters(int width, int height) | 共通寸法上限内 |
| COLOR_ADJUST | ColorAdjustParameters(int brightness, double contrast, double saturation) | 5章の範囲 |
| BLUR | BlurParameters(int radius) | 0～20 |
| MOSAIC | MosaicParameters(int blockSize) | 1～128 |
| その他 | NoParameters | 他のパラメータ型は拒否 |

ProcessingResultは適用したoperation、parametersも保持する案とし、再実行の入力を明確にする。CancellationTokenはisCancellationRequested()、ProgressListenerはonProgress(double fraction)を基本案とする。同期処理での取消はProcessingException(code=CANCELLED)、非同期の失敗はFutureの例外完了へ統一する案とする。

### 変更範囲

同サイズの全体処理は入力・出力の全面を変更候補範囲として返す。実画素差の有無と履歴タイル化はAが判断する。寸法変更は旧画像全面と新画像全面、geometryChanged=trueを返す。画素が変わらない場合も出力は独立コピーとし、Aは不要な履歴登録を省略できる。

## 4 幾何変換の詳細

| 処理 | 入力と計算 | 出力 |
| --- | --- | --- |
| 右90度 | 入力W×H、元(x,y)を(H-1-y,x)へコピー | H×W |
| 左90度 | 元(x,y)を(y,W-1-x)へコピー | H×W |
| 左右反転 | 元(x,y)を(W-1-x,y)へコピー | W×H |
| 上下反転 | 元(x,y)を(x,H-1-y)へコピー | W×H |
| 切り抜き | 完全に画像内の正幅・正高さの矩形を指定 | 矩形幅×矩形高さ |
| リサイズ | 正のtargetWidth、targetHeightを明示 | 指定幅×指定高さ |

回転・反転・切り抜きは画素の再配置で、補間しない。切り抜きの範囲外は自動補正せず拒否し、Aが選択範囲を確定してから渡す。切り抜き結果のレイヤー位置やキャンバス位置の更新はAの責務とする。

### 縦横比の計算

Aは幅を基準にする場合、height=max(1,floor(sourceHeight×targetWidth/sourceWidth+0.5))で高さを計算する。高さ基準は逆式。Bは最終的な幅と高さを受け取り、重複した縦横比判定をしない。同じ寸法なら独立コピーを返す。ARGB_PREの独立コピーは同形式のRasterデータを複製し、getRGB→setRGBの往復による半透明画素の丸め誤差を避ける。

### リサイズの補間

設計値としてバイリニア補間を標準とする。出力画素中心からsx=(dx+0.5)×W/Wout-0.5、sy=(dy+0.5)×H/Hout-0.5で入力へ対応付け、画像端にクランプする。隣接4画素のalphaと乗算RGBを同じ重みで補間し、半透明境界の黒縁を防ぐ。端点は座標を先に[0,W-1]・[0,H-1]へクランプし、floorで左上点を取得、右下点は各上限でクランプする。重みはクランプ後の小数部分から算出する。最終alphaと乗算RGBをfloor(v+0.5)で整数化し、RGB各成分を0～alphaへクランプする。algorithmVersionはv1とする。

### 対象画像と複数レイヤー

Version 1のB契約は1枚のラスタ画像単位。画像全体の処理が必要な場合はAが合成スナップショットまたは各レイヤー画像を準備する。選択レイヤーの画素回転と、SHOULDのレイヤー変形プロパティは別操作とする。キャンバストリミングで各レイヤー位置をずらす場合、Aが全結果を揃えて1つのCommandにする。

### 基準例

```
input  3 x 2       rotate right  2 x 3
A B C              D A
D E F              E B
                   F C
```

行単位で取消を確認する。出力を作り終える前に失敗・取消が起きた場合は結果を公開せず、入力を維持する。

## 5 色調整と基本フィルター

設計値：明るさbは整数-255～255、コントラストcは0.0～3.0、彩度sは0.0～3.0。既定値はb=0、c=1、s=1。UIは利用者向けの目盛りからこの値へ変換する。round(v)=floor(v+0.5)、clamp(v)=min(255,max(0,v))として各段階で8bitへ丸める。alphaは変更しない。

### ColorAdjustProcessor

```
1 brightness:  C1 = clamp(round(C + b))
2 contrast:    C2 = clamp(round((C1 - 128) * c + 128))
3 luminance:   Y = 0.299*R2 + 0.587*G2 + 0.114*B2
4 saturation:  C3 = clamp(round(Y + s*(C2 - Y)))
```

R、G、Bへ同じ順番で適用する。コントラスト0は一様な128、彩度0はグレースケールになる。アルファを保持し、完全透明画素は0へ正規化する。プレビューと本適用は同じ演算とパラメータを使う。

### FilterProcessor と ImageFilter

| フィルター | 非乗算RGBの演算 | 検証例 |
| --- | --- | --- |
| GrayscaleFilter | Y=round(0.299R+0.587G+0.114B)<br>R′=G′=B′=Y | 赤255,0,0 → 76,76,76 |
| NegativeFilter | R′=255-R、G′=255-G、B′=255-B | 10,20,30 → 245,235,225 |
| SepiaFilter | R′=clamp(round(.393R+.769G+.189B))<br>G′=clamp(round(.349R+.686G+.168B))<br>B′=clamp(round(.272R+.534G+.131B)) | 白 → 255,255,239 |

FR-14は複数の基本フィルターを要求する。本設計ではグレースケール、セピア、ネガの3つを実装する。フィルターIDは固定enumにして履歴再計算時の名称変更を避ける。

### 再現性

画像の行を上から、画素を左から走査し、乱数や時刻に依存しない。演算順と丸めはalgorithmVersion=v1として固定する。画像の色表現を変えた場合は版本を上げ、古い履歴に新式を適用しない。ColorAdjustの既定パラメータでは演算を省略して同形式Rasterを独立コピーし、生の乗算画素が不変になることを単体テストする。回転・反転・切り抜きも正規化後の乗算画素を再配置し、非乗算化を挟まない。

## 6 ぼかしとモザイク

### BlurProcessor

設計値：GaussianBlurのradiusは整数0～20、既定3。0は独立コピー。radius>0ではsigma=max(radius/3.0,0.5)、k[i]=exp(-i²/(2sigma²))、i=-radius～radiusとし、重みの総和が1になるよう正規化する。水平・垂直の2パスで実装し、画像端は最寄り画素を複製する。

alphaと乗算RGBを同じカーネルで畳み込む。途中は浮動小数点で保持し、最終出力だけ丸める。透明画素のRGBを不透明な色として混ぜない。内部は行リングバッファ等で作業領域を抑え、4チャンネルの全画像double配列を複数持たない。処理量はO(W×H×radius)。

### MosaicProcessor

設計値：blockSizeは整数1～128、既定12。画像左上(0,0)をブロック原点とし、b×bごとに同じ色を塗る。右端・下端の不完全ブロックは存在する画素だけを対象にする。1は独立コピー。画像や選択レイヤーの全体だけを対象とし、任意範囲への適用をMUSTへ混ぜない。

```
N = number of pixels in a block
Aout = round(sum(A) / N)
if sum(A) == 0: RGBout = (0,0,0)
else: Cout = round(sum(C*A) / sum(A))
fill block with (Aout, Rout, Gout, Bout)
```

モザイクはalphaも平均する設計とする。半透明境界はブロック平均となるため形状が多少変化する。積算にはlongまたはdoubleを用いて桁あふれを避ける。計算量はO(W×H)。

### 進捗と取消

ぼかしは水平パスを0～50%、垂直パスを50～100%とする。モザイクは完了ブロック行数から算出する。通知は最大10回/秒を設計値とし、行またはブロック行ごとに取消を確認する。取消時はCANCELLEDを返し、部分画像を適用しない。

### 品質確認

単色画像が変化しないこと、1×1画像が処理できること、透明背景に不透明色を置いた画像に黒縁が出ないことを確認する。ぼかしの画素期待値は小サイズ画像に対する独立した2次元畳み込み結果と比較する。

## 7 AIのJava契約と座標

```
interface AiSegmentationService {
  CompletionStage<RegisteredImage> registerImage(
      BufferedImage snapshot, String sourceRevision);
  CompletionStage<AiMaskResult> segment(AiRequest request);
  CompletionStage<Void> releaseImage(String imageId);
}
AiRequest(imageId, sourceRevision, requestId, points)
PromptPoint(x, y, label) // label: POSITIVE or NEGATIVE
AiMaskResult(imageId, sourceRevision, requestId,
             candidates, sourcePoints, deviceUsed)
MaskCandidate(candidateId, mask, score)
```

RegisteredImageの提案項目はimageId、sourceRevision、width、height、serviceInstanceId。キャンセルを呼出し側から行えるよう、戻り値をCompletionStageのままにするか取消ハンドル付きにするかはAG-01で合意する。B内部は取消可能なCompletableFutureで実装し、cancelを通信・待機ジョブの取消へ伝播する。

これは提案契約。sourceRevisionはAが管理する編集対象画像の世代を識別する不透明文字列。requestIdは各推論要求のUUID。imageIdはPython側が発行する。candidateIdは結果ごとに新規発行し、以前の候補IDを流用しない。selectedCandidateIdはAの選択状態に保持し、Bは候補を自動確定しない。

### 画像登録とクリック

登録は選択中の対象ラスタ画像の固定スナップショットを基本とする。透過画像は原寸を維持し、AI入力のRGB化では白背景へ合成する設計とする。抽出にはAが保持する元RGBAを使う。合成画像を対象にしたい場合も、Aがその画像を明示的に渡す。

点座標は原寸画像の画素中心を表す整数、0≤x<W、0≤y<H。Aがキャンバスからレイヤー座標へ逆変換し、floorで画素を決定する。サービスは内部のモデル入力座標へ変換する。点は1～64個、少なくとも1個は正クリック。追加補正では最新のクリック列全体を送る。Version 1の必須APIは点入力のみとする。

### 同一画像への追加要求と応答順序

BのJavaクライアントはserviceInstanceIdとimageIdの組ごとにlatestRequestIdを保持する。segment受付時に最新IDを置き換え、同じ画像の未送信旧要求を取り消す。受信した応答のrequestIdがその送信要求と違う場合はINVALID_RESPONSE。送信要求とは一致するがlatestRequestIdではない場合はCANCELLED相当で破棄し、候補を返さない。画像削除、サービス再起動、AI操作取消でも該当の最新IDを無効化する。

これはB内部の応答抑制である。UI反映までの間に新しいクリックが生じる競合もあるため、A側でも最新requestId、対象ID、画像世代、AIセッションの有効性を確認する必要がある。A側の保持方法と確定処理はAG-02・AG-03で合意する。登録処理にも世代を付け、登録完了時に対象が失効していればIDを解放する。

### マスクの表現と確定処理

maskは元画像と同寸法の8bitグレースケールPNG。画素0は背景、255は選択領域。二値化はモデルのmask_thresholdを用いる。Java側ではTYPE_BYTE_GRAY相当に読み、Rasterのサンプル値が0/255であることを検証する。getRGBの色空間変換値をマスクとして使わない。scoreは有限の予測スコアで、確率の保証として扱わない。

Aの確定処理は元RGBを保持し、alpha′=round(alpha×mask/255)で透明な新規画像を作る。レイヤー作成とCommand化はAが担当し、元画像を削除・変更しない。新画像を原寸で保持する場合は元レイヤーと同じ位置に置く。候補0件はHTTP 422、error.code=NO_RESULTで返す。空のcandidatesを成功応答として返さない。

## 8 AI HTTP仕様

提案契約：HTTP APIの互換バージョンはヘッダーX-Api-Version: 1で宣言する。パスは技術検証メモの候補を維持する。JSONはUTF-8、multipartの画像はimage/png。すべての要求に起動時発行のBearerトークンを付ける。以下の数値と上限は設計値。

| メソッドとパス | 入力 | 成功応答と条件 |
| --- | --- | --- |
| GET /health | 認証とAPIバージョン | 200 JSON state, aiReady, psdReady, device, modelVersion |
| POST /image | multipart: image, sourceRevision | 201 JSON imageId, sourceRevision, width, height |
| POST /segment | JSON 下記 | 200 JSON 1～3候補と原寸PNG |
| DELETE /image/{id} | 登録済みimageId | 204 キャッシュ破棄。未知IDも204 |

```
{"imageId":"img-...","sourceRevision":"rev-7",
 "requestId":"uuid", "points":[
   {"x":120,"y":80,"label":1},
   {"x":130,"y":90,"label":0}]}
{"imageId":"img-...","sourceRevision":"rev-7",
 "requestId":"uuid","width":1920,"height":1080,
 "deviceUsed":"mps","candidates":[
   {"candidateId":"c-...","score":0.91,
    "maskPngBase64":"<PNG bytes encoded as base64>"}]}
```

pointsのlabelは1=正、0=負。応答候補はスコア降順で同点ならモデル出力順。空マスクだけを除外し、選択肢を勝手に1件に絞らない。マスクの転送は最初の実装をBase64 JSONに統一し、候補画像を別URLから取得する必要をなくす。

### HTTPの正常系と異常系

すべての要求にX-Request-IdのUUIDを付け、応答にも同じヘッダーを付ける。segmentのJSON requestIdとヘッダーが違う要求は400 INVALID_ARGUMENT。全応答にX-Api-Version: 1を付ける。バージョン指定の欠落・非対応は426 API_VERSION_UNSUPPORTEDで返す。応答版の欠落・不一致はJavaでAPI_VERSION_UNSUPPORTEDとする。

| 条件 | HTTPとcode | 自動再試行 |
| --- | --- | --- |
| 全候補が空 | 422 NO_RESULT | なし。クリック補正を促す |
| imageIdとsourceRevisionが登録内容に不一致 | 409 REVISION_MISMATCH | なし |
| PSDヘッダーがPSDではない、PSB | 415 UNSUPPORTED_FORMAT | なし |
| モデル重み未配置・ロード不可 | 503 MODEL_INIT_FAILED | なし。通常編集とPSDは継続可能 |
| 待機キュー満杯 | 429 BUSY | なし |
| 実行待ち・処理の期限超過 | 504 TIMEOUT | なし |
| 解読できない画像やPSD | 422 INVALID_IMAGE / PSD_PARSE_FAILED | なし |

この表は10章の共通表を補完する。HTTPステータスだけでコードを推測せず、error.codeを検証して例外へ変換する。未知コード、必須項目欠落、JSONでないエラー本文はINVALID_RESPONSEとし、HTTPステータスを診断情報として保持する。想定するローカルサービスの全エラーを同じerrorオブジェクトへ正規化する。

/healthのstateはSTARTING、READY、DEGRADED、STOPPING。READYはAIとPSD双方の準備完了、DEGRADEDは片方のみ利用可能を示す。aiReady=falseでPSDのみ動作する状態をプロセス起動失敗と混同しない。serviceInstanceIdはプロセス起動ごとのUUIDで、healthと画像登録応答へ含める。

### 応答の検証

HTTPの成功コードだけで成功判定しない。imageId、sourceRevision、requestId、原寸、候補数、候補ID重複、有限score、PNG形式・寸法・二値画素を検証する。不一致はINVALID_RESPONSE。余分なJSONフィールドは無視できるが、必須フィールド欠落と未知のAPIメジャー版は拒否する。

上限：画像アップロード64MiB、segment JSON 64KiB、AI応答48MiB、PNG展開後は共通総画素数上限。本文はストリームで上限まで読み、超過時に打ち切る。Content-Lengthだけを信用せず、PNG寸法も展開前に確認する。

## 9 MobileSAMとキャッシュ

### 起動と推論

モデルは1プロセスに1個ロードし、evalと勾配無効の推論モードで実行する。MPSを優先し、利用できない環境ではCPUを選ぶ。ロード後にダミー画像の埋め込みと点推論を1回行う。完了まではaiReady=false。PSDはモデル準備とは独立して利用可能にする。

画像登録で元RGB画像、原寸、モデル前処理情報、埋め込みをimageIdに紐付ける。segmentでキャッシュの埋め込みをPredictorへセットし、クリック列を渡してmultimask出力を取得する。複数画像の共有Predictor状態を同時に書き換えない。モデル操作は単一ワーカーで直列化する。画像の登録・埋め込み再生成・推論・CPU切替を同じ直列実行器で扱い、CPU切替中に別要求が古い埋め込みを参照しない。MPS埋め込みを常駐させずCPUへ退避し、キャッシュ予算に計上する。利用時だけ必要なデバイスへ転送する。

### MPSからCPUへの再試行

モデルロード、ウォームアップ、埋め込み、推論のMPS実行例外に限り、CPUへ切り替えて失敗した論理処理を1回だけ再試行する。入力不正、画像不明、取消、NO_RESULT、HTTPタイムアウトはデバイス切替の理由にしない。CPU再試行も失敗したらINFERENCE_FAILEDまたはMODEL_INIT_FAILEDとする。

切替時はCPUモデルを準備し、MPS埋め込みを全破棄する。保持したRGBから対象画像のCPU埋め込みを再計算して元のクリック列を適用する。他のimageIdは保持し、次回利用時に埋め込みを遅延再生成する。切替後はプロセス終了までCPUを維持し、自動でMPSへ戻さない。途中候補は公開しない。CPU切替時はPredictorが保持する旧埋め込み参照と旧デバイスのモデル参照を解放し、失敗したMPSモデルをCPUモデルと長期間二重保持しない。CPUロードも同じ処理の期限に含める。

### キャッシュ管理

設計値：最大2画像かつCPU側の元RGB・埋め込み実バイト合計128MiB、未使用10分で失効。加速器のモデル・作業メモリは別途監視する。LRUで非使用中エントリを破棄し、処理中のエントリは固定する。登録1件で予算を超える場合はRESOURCE_LIMIT。削除と推論が競合したら削除予定を付け、利用終了後に解放する。

画像画素が変わったら新imageIdを登録し、古いIDを破棄する。位置・レイヤー透明度だけの変更では登録画像が同一なら埋め込み再計算は不要だが、結果適用の世代判定はAが行う。サービス再起動では全IDが失効し、Java側も登録状態を消す。未知IDは404 IMAGE_NOT_FOUNDとして再登録を促す。

## 10 サービス起動とエラー処理

### 起動配置と実行方法

B内部のサービス配置は以下を初期構成とする。アプリ全体のビルド構成と配布方法はAG-08の合意対象とする。

```text
python-service/
  pyproject.toml
  requirements.lock
  src/editor_helper/__main__.py
  src/editor_helper/api.py
  src/editor_helper/model_runtime.py
  src/editor_helper/image_cache.py
  src/editor_helper/psd_importer.py
  models/model-manifest.json
  models/mobile_sam.pt
  .venv/
```

Pythonの実行パスは設定値pythonExecutableで受け取り、作業ディレクトリのPATH依存で選ばない。開発時の初期候補はpython-service/.venv/bin/python。Bは実装初日に技術検証と同じ環境の実行版を回収し、Python、PyTorch、MobileSAMコミット、psd-tools、FastAPI、ASGIサーバー等をrequirements.lockと環境記録に固定する。既存資料に正確な版がない依存を検証済みとして扱わず、クリーン環境での再現確認を完了条件とする。

ProcessBuilderの引数配列は次を基準とする。workingDirectoryはpython-serviceの絶対パス、srcはパッケージとして仮想環境へインストールする。

```text
[pythonExecutable, "-m", "editor_helper",
 "--host", "127.0.0.1", "--port", "0",
 "--model-manifest", absoluteModelManifestPath]
```

環境変数EDITOR_HELPER_TOKENへ暗号学的乱数32bytesのURL-safe文字列を渡す。モデルmanifestはmodelType、relativeWeightsPath、sha256を必須とし、実装時に実ファイルのハッシュを記録する。重み欠落・ハッシュ不一致はAIをMODEL_INIT_FAILEDにし、PSDサービスの起動を妨げない。

### 起動通知と準備完了判定

Pythonはホストを127.0.0.1に固定し、ポート0でソケットをbindして得たソケットをASGIサーバーへ引き渡す。ポート予約のためにbind後closeしてから再bindする方式を避ける。HTTPが受付可能になった時点で、標準出力へ次のJSONを1行だけ出力してflushする。

```json
{"event":"listening","apiVersion":1,"host":"127.0.0.1","port":49152,"pid":12345,"serviceInstanceId":"uuid"}
```

Javaはプロセス開始から10秒以内に通知を待ち、最大8KiBの1行、event、API版、host、ポート1～65535、起動した子のPIDを検証する。不正通知・子終了・通知期限超過ではその子だけを停止しSERVICE_UNAVAILABLEとする。通信とモデル準備を混同せず、通知後に認証付きhealthを500ms間隔で確認する。起動から60秒以内にAIが準備できなければAIを利用不可として通常編集を継続し、動作中のPSDサービスは停止しない。自動再起動ループは行わない。

### LocalServiceManagerのライフサイクル

STOPPED → STARTING → RUNNING → STOPPING → STOPPEDを基本とし、起動失敗はFAILED。RUNNINGは検証済みhealthへ到達した状態とし、AIとPSDの利用可否は別フラグで管理する。Javaはシェルを経由しないProcessBuilderの引数配列で設定済みPythonを起動する。Pythonは127.0.0.1の動的ポートへbindし、起動ハンドシェイクにポート・PID・API版を標準出力のJSON 1行で通知する。診断ログは標準エラーへ出し、両方を継続して読み取る。

トークンはJavaが起動ごとに生成し環境変数で渡す。通信先はハンドシェイクのポートを検証して127.0.0.1へ固定し、リダイレクトとプロキシを無効化する。画像、クリック列、トークンをログに残さない。モデル重みは事前配置し、実行中に自動ダウンロードしない。

設計値：接続2秒、health 2秒、画像登録30秒、segment 15秒、PSD 60秒、起動準備60秒。接続・要求タイムアウトを別々に設定する。自動HTTP再送は行わず、利用者の再試行は新requestIdにする。終了時は受付停止後にJavaが所有する子へ終了要求、5秒待機後に強制停止。終了要求は子Process.destroy()とし、Python側のSIGTERMハンドラーで受付停止、待機ジョブの取消、一時ファイル削除を行う。期限内に終了しなければdestroyForcibly()を用い、終了待ちを回収する。無関係なPythonプロセスを終了しない。

| HTTPまたは例外 | 共通コード | Aへ渡す次の操作 |
| --- | --- | --- |
| 400 / 413 / 422 | INVALID_ARGUMENT / RESOURCE_LIMIT | 入力範囲や画像サイズを見直す |
| 401 | AUTH_FAILED | 子サービスを再起動する |
| 426 | API_VERSION_UNSUPPORTED | アプリとサービスの版を揃える |
| 422 | NO_RESULT / INVALID_IMAGE | クリック補正または別画像を試す |
| 415 | UNSUPPORTED_FORMAT | 対応形式を指定する |
| 504 | TIMEOUT | 処理完了を待って再試行 |
| 404 | IMAGE_NOT_FOUND | 画像を再登録する |
| 409 | REVISION_MISMATCH | 最新画像からやり直す |
| 429 | BUSY | 実行中処理の完了後に再試行 |
| 503 | MODEL_NOT_READY / MODEL_INIT_FAILED | 準備完了を待つ、環境設定を確認 |
| 500 | INFERENCE_FAILED / PSD_PARSE_FAILED | 処理をやり直す、別ファイルを試す |
| 接続失敗 / 要求時間超過 | SERVICE_UNAVAILABLE / TIMEOUT | サービス再起動または再試行 |
| 応答不正 / 取消 | INVALID_RESPONSE / CANCELLED | 再試行、または無通知で中止 |

```
{"error":{"code":"INFERENCE_FAILED",
 "message":"推論に失敗しました",
 "requestId":"uuid","retryable":true}}
```

例外はProcessingException、AiServiceException、PsdImportExceptionにcode、requestId、原因を保持する。Java側の表示文言はコードに基づき安全な日本語へ変換する。正常編集はAIがFAILEDでも継続する。再起動は未完了要求を失敗させ、IDを無効化してから行う。

## 11 PSD取り込みの契約

```
interface PsdImportService {
  CompletionStage<PsdImportResult> importPsd(Path file);
}
PsdImportResult(canvasWidth, canvasHeight, nodes,
                rasterAssets, warnings, schemaVersion)
PsdNode(id, parentId, siblingIndex, name, kind,
        x, y, width, height, selfVisible, effectiveVisible,
        opacity, effectiveOpacity, blendMode,
        assetId, importDisposition, originalMetadata)
ImportWarning(code, nodeId, message, disposition,
              sourceKind, unsupportedFeature)
```

idは取り込みごとに生成する一意ID。siblingIndexは親内の下から上へ0始まりで統一し、Pythonライブラリの走査順をそのまま公開しない。x、yはPSDキャンバス基準の絶対整数座標で、負値とキャンバス外を許容する。opacityは0～1の浮動小数点値。kindには元の種類を保持する。

### PSD転送と返却

提案API：POST /psd/importへmultipartのfileを送る。ローカルファイルパスをサーバーに渡して任意のファイルを読ませる構成にしない。成功時はapplication/zipを返し、manifest.jsonとassets/{assetId}.pngを含める。manifestのschemaVersionは1。ノードと警告はJSON、ラスタは原寸のRGBA PNGとする。

```
manifest.json
  schemaVersion, canvasWidth, canvasHeight
  nodes: [PsdNode ...]
  assets: [{assetId, path, width, height}]
  warnings: [ImportWarning ...]
```

設計値：PSD入力128MiB、最大500ノード、展開ラスタ総量256MiB、ZIP応答256MiB。総量はwidth×height×4をlongで加算する。制限超過は部分取り込みでごまかさずRESOURCE_LIMITとする。PSBはVersion 1の保証対象外として拒否する。

### Java側の検証と変換

ZIPは一時領域で処理し、絶対パス、..、重複名、manifestにない参照を拒否する。可能ならメモリ上で必要エントリだけを読む。圧縮後サイズに加えて展開バイト数とPNG寸法を検証する。親の不在、循環、一意ID違反、opacityの非有限値、asset寸法不一致をINVALID_RESPONSEとする。

JavaはRGBAをTYPE_INT_ARGB_PREへ変換してDTOを返す。Aが内部RasterLayerへ登録し、ノード階層のメタデータを保持する。BはLayerManagerへ触らない。実効可視性と元属性の実効透明度をDTOへ提供する。ただしeffectiveOpacityは診断用とし、12章の描画用opacityと混同しない。

## 12 PSD変換と警告の規則

| 要素 | 取り込み動作 | 警告 |
| --- | --- | --- |
| 通常ラスタ | 画素を抽出、名称・位置・自己表示・透明度を保持 | 正常時なし |
| 通常グループ | 安全に展開可能なpass-throughのみ子を展開。それ以外はグループ合成 | 合成で個別編集を失う場合FLATTENED |
| Type / Shape / SmartObject | 取得可能な見た目をRGBAへラスタライズ | RASTERIZED 元の編集情報を保持できない |
| 調整 / 効果 / 非標準blend | 影響範囲を合成し、代替ラスタへ置換 | FLATTENED 種類と対象範囲を列挙 |
| 空または画素なし | メタデータだけ保持、描画assetなし | 必要な場合EMPTY_LAYER |
| 抽出失敗 | 代替合成を試し、不可能なら取り込み失敗 | EXTRACTION_FAILED |

### 二重描画を防ぐ変換

通常ラスタの抽出画像はレイヤー透明度を未適用にする。抽出方式が透明度を焼き込む場合は描画用opacity=1にし、元の値をoriginalMetadataへ残す。元の表示属性を変更せずに抽出し、非表示レイヤーもRGBAを取得できる経路を使う。

親の実効表示はselfVisibleと全祖先のselfVisibleの論理積。effectiveOpacityは元属性の祖先積として診断用に保持できるが、子ラスタの描画透明度として使用しない。グループ透明度は子同士を合成した結果へ一度適用する必要があり、各子へ掛けると重なり部分が変わるためである。

BのVersion 1変換では、平坦なRasterLayerへの登録を前提に、透明度1・効果なし・調整なし・明示的なpass-throughグループだけを子ノードへ展開できる。グループ合成が必要なnormal等の隔離グループ、透明度1未満、マスク・クリッピング・効果・調整・未知の合成規則を持つグループは、グループを代替ラスタ化する。合成が外部の背景・兄弟へ依存するpass-through調整等は、影響先を含む祖先または文書全体へ代替範囲を広げる。未知の状態を安全に展開可能とは判定しない。

originalMetadataには元のkind、位置、自己表示、opacity、blendMode等を保持する。importDispositionはRASTER、GROUP_METADATA、REPLACEMENT_RASTER、METADATA_ONLY、EMPTY_METADATAとし、描画対象はRASTERとREPLACEMENT_RASTERだけとする。これはAG-05のDTO提案であり、B内部では同じ区分で二重描画を防ぐ。

調整レイヤーや描画モードが兄弟・下位レイヤーへ影響する場合は、安全に再現できる最小の祖先グループ全体を代替ラスタにする。範囲を分離できない場合は文書全体を代替合成する。代替画像に透明度や効果を焼き込んだ場合、描画用ノードopacityは1にし、元の属性は原ノードのメタデータへ残す。置換範囲の子孫はmetadataOnlyとして描画対象から除外する。

非表示グループは表示状態を維持したうえでラスタ取得する。代替画像を取得できない場合は成功扱いで黙って欠落させずPSD_PARSE_FAILED。全体フラット化は個別レイヤー編集を失うため、警告に明記する。SHOULD受入では通常ラスタと展開可能なpass-throughグループ内の個別編集が成立するPSDも別に確認する。

### 警告DTOの内容

warning.code、元nodeId、元種別、非対応要素、適用した処置を保持する。「互換性に問題がある」だけで終わらせず、「調整レイヤーを含むグループを1枚の画像へ変換」のように利用者が影響を判断できる内容にする。TextLayerとして再編集できると誤認させない。

### 抽出品質の検証

ラスタ透明度が二重適用されないこと、非表示の親を持つ子が勝手に表示されないこと、負座標、Unicode名、階層順を確認する。PSDの合成プレビューとの差分は固定サンプルで比較し、許容できない差分は対応扱いにせず警告または代替合成へ移す。

## 13 非同期実行と履歴への受け渡し

### 実行上限とキャンセル

以下のB内部の取消・期限管理は、共通の取消ハンドルをどの型で公開するかとは独立して実装する。

設計値：Javaの重い画像処理は1ワーカー、待機キュー2件。AIのモデル処理はPythonの1ワーカー、待機2件。PSD解析は別の1ワーカーにし、モデル操作と状態を共有しない。PSDの待機上限は1件。上限を超えた要求はBUSY。Executorを用途別に分け、HTTP完了待ちで画像演算のワーカーを占有しない。

AはUIスレッドで対象ID・画像世代・パラメータを固定し、安定した入力スナップショットを渡す。Bはバックグラウンドで処理し、完了結果を返す。AはUIスレッドで対象の存在、世代一致、操作の取消状態を再確認してから適用する。別画像を開いた後の古い結果は破棄する。AIでは同一画像世代でもクリック列の更新で結果が古くなるため、最新requestIdとAIセッション一致も必須とする。このA側の適用条件はAG-02で合意する。

画像処理は協調取消。HTTP取消では未送信要求を除去し、送信済みなら接続待ちを中止して結果を適用しない。実行中のモデル演算を即座に中断できる保証は持たせず、完了まで処理枠を占有させる。タイムアウト後も連打で推論が無制限に増えないよう、サーバー側のキュー上限を適用する。

Pythonはリクエスト受付時に単調増加時計でdeadlineを設定する。imageは25秒、segmentは12秒、PSDは55秒を設計値とし、キュー待ちを含める。開始直前に期限を確認し、期限切れなら実行せず504 TIMEOUT。モデル呼出し等の中断できない区間は完了までワーカーを占有し、終了後に期限・切断・取消フラグを確認して結果を破棄する。モデル内へ非同期例外を投げたり、タイムアウトごとに新しい無制限スレッドを作ったりしない。

待機中のHTTP切断は当該ジョブを除去し、ジョブ用リソースを解放する。実行中の切断では候補を公開せず、完了後に参照カウントと一時ファイルを解放する。JavaがTIMEOUTで待機を終えた後もサーバー演算が続く場合があるため、再試行は新IDでキュー上限に従う。キャッシュ削除は処理中エントリの削除予定とし、同じIDの新規推論は404へ統一する。

### 履歴管理との契約

Bは変更範囲、geometryChanged、決定性、処理種別・パラメータ・algorithmVersionを渡す。Aは変更前データを最大256×256pxのタイルへ分割し、実データ量256MiBの履歴予算で管理する。BはCommandや履歴スタックを生成しない。

同サイズの決定的処理は、Aが変更前タイルと演算パラメータを保持し、Redoで同じ版本の処理を再実行できる。回転・リサイズ・切り抜きは寸法変更を伴うため、Aが旧寸法と復元に必要な旧画像タイル群を保持する。タイル分割しても全面変更の総保存量が消えるわけではない。

AIレイヤー生成のUndoはAが新規レイヤーを削除する1操作。Redoでは保存済みの確定画像を再追加し、再推論しない。プレビュー、候補変更、失敗、取消、古い結果の破棄は履歴とdirtyを変更しない。メモリ不足時は結果を適用せず、Aへ処理失敗を通知する。

### 4K処理とメモリ

3840×2160×4=33,177,600bytes、約31.64MiB。入力固定画像と出力だけで約63.3MiBに加え、表示キャッシュ・履歴・作業領域が必要。中間全画像コピーを増やさず、処理を直列化する。変更前のデータ保全に必要なコピーを無理に省かず、最大画像と履歴上限を合わせて実機で評価する。

## 14 単体とサービス結合テスト

各ケースは入力、期待結果、判定根拠を固定する。JavaはJUnit相当、Pythonはpytest相当を候補とし、依存版はチームで固定する。HTTP異常系はモックで検証し、MobileSAMとpsd-toolsの実機テストを別に実施する。

| IDと対象 | 入力または操作 | 期待結果 |
| --- | --- | --- |
| P01 幾何変換 | 3×2の識別色画像、左右回転、反転 | 4章の対応式に完全一致、回転4回で元画素 |
| P02 Crop | 端に接する矩形、範囲外、幅0 | 有効部分は完全一致、不正は拒否、入力不変 |
| P03 Resize | 1×1、同寸、2×2→3×3、透過境界 | 寸法と補間式が一致、黒縁なし |
| P04 色 | 赤、白、黒、半透明色、既定値と上下限 | 5章の期待値、alpha保持、入力不変 |
| P05 Blur | 単色、1×1、透明背景と色点、radius0と20 | 独立2D計算との差1以内、端処理一致 |
| P06 Mosaic | 5×3、block2、透明色混在、block1 | 端ブロックとalpha加重平均が一致 |
| P07 共通 | null、NaN、寸法積超過、取消 | typed error、部分結果なし、入力不変 |
| A01 登録と再利用 | 同一imageIdへ連続クリック | 埋め込み再計算なし、最大3候補とscore |
| A02 補正 | 正点後に負点を加え、クリック列全送信 | requestId対応、元寸法の二値マスク |
| A03 フォールバック | MPS演算例外、CPU成功／失敗 | CPU再計算は1回、二重再試行なし |
| A04 通信異常 | 接続拒否、時間超過、壊れたPNG、ID違い | 該当エラーへ変換、Document変更なし |
| A05 キャッシュ | LRU、TTL、削除競合、再起動 | 使用中保持、失効IDは404、予算内 |
| S01 PSD基礎 | 通常層、親非表示、負座標、Unicode名 | 順序・実効表示・位置・透明度一致 |
| S02 PSD代替 | テキスト、スマート、調整、効果 | ラスタ化と警告、二重描画なし |
| S03 PSD異常 | 破損、ZIP traversal、循環親、巨大PNG | 入力拒否、既存状態を保持 |

加えて、同一入力からの決定性、成功・失敗・取消後の一時ファイル削除、ログへの画像やトークン混入がないことを確認する。

## 15 結合計画と実装前の合意

### 担当Aとの結合テスト

| ID | 手順と合格条件 |
| --- | --- |
| I01 基本処理 | PNGを開く→B処理→表示→Undo→Redo→保存。画素と寸法が期待値へ復元する。 |
| I02 AI分離 | 候補を複数表示し、最高score以外も選べる。追加正負点、再試行、取消、確定が動く。元画像を維持し、生成レイヤーをUndo/Redoできる。 |
| I03 非同期競合 | 処理中に対象を編集・削除、別文書へ切替、取消。古い結果が新状態へ適用されない。 |
| I04 障害と継続 | サービス強制終了、モデルなし、CPU失敗。原因を表示し、基本編集と保存が継続できる。 |
| I05 PSD | 階層と警告を表示し、通常ラスタを個別に移動・非表示化できる。代替合成の重複がない。 |

### 性能評価

1920×1080と3840×2160で、処理時間、初回登録、追加クリック、Javaヒープ、Pythonメモリ、UI応答を記録する。AI初回5秒以内・追加クリック1秒以内、2K一般処理概ね1秒以内は資料の目標として評価する。資料のPoCはMPSウォーム追加クリック33ms、4Kぼかし53.9msだが、別アルゴリズム・端末・通信込みの保証値にしない。4Kで遅い場合はAの縮小プレビューと原寸確定を分ける。

### 追加の回帰テスト

| ID | 条件 | 合格条件 |
| --- | --- | --- |
| A06 応答順序 | 同じimageId・世代で要求a後にbを送信、aが後着 | Bはaを公開せず、A側も最新requestIdだけを表示 |
| A07 APIと無認識 | API版不一致、全候補空、error JSON破損 | API_VERSION_UNSUPPORTED、NO_RESULT、INVALID_RESPONSEへ区別 |
| A08 期限と取消 | キュー内で失効、実行中切断、連続再試行 | 失効ジョブは未実行、実行中の枠は完了まで維持、無制限増加なし |
| R01 サービス起動 | 動的ポート、通知不正、重みなし、子早期終了 | ポート再bind競合なし、失敗を区別、AI不可でもPSD利用可能 |
| R02 終了 | 待機・実行中に終了、停止期限超過 | 所有する子だけを停止、一時領域とプロセス待ちを回収 |
| S04 グループ透明度 | 不透明な赤と青を重ね、normalグループopacity=0.5 | グループ合成後の重なり部分alphaは約128。子への0.5適用で生じる約191にしない |
| S05 代替範囲 | 調整が兄弟へ作用、非表示グループ、代替親あり | 影響範囲を含めて合成、表示保持、子孫の二重描画なし |
| P08 半透明の不変性 | ARGB_PRE低alpha画素、既定色調整・同寸Resize | 正規化済み入力の生画素と完全一致、出力は独立 |

S04は不透明背景のないグループを合成してからグループ透明度を適用する固定サンプルとする。ライブラリ別の抽出結果を同じ基準へ正規化できるかを実機確認する。

### 7週間の担当Bの成果物

| 週 | 成果物と合流条件 |
| --- | --- |
| 1 | 共通contract初版、Processor/AI/PSDのStub、Python起動骨格 |
| 2 | 回転・反転・Resize・Crop・色調整・基本Filterと単体検証 |
| 3 | ぼかし・モザイク、HTTPクライアント、PSD importerの基本 |
| 4 | 常駐・キャッシュ・CPU切替・3候補、AIレイヤー生成まで共同結合 |
| 5 | 4K非同期、AIエラー、PSD安定化、MUST受入フロー |
| 6 | 重大不具合修正、SHOULD完成度調整、回帰確認 |
| 7 | 提出版固定、性能記録、起動手順、ライセンス一覧、デモ |

### 相互レビューで固定する項目

[担当Aとの合意事項](../coordination/担当Aとの合意事項.md)を正本とする。各項目にBの提案、Aに確認する点、合意前に進められる作業、完了条件を記載する。共通contractの変更は合意後に行い、本書の該当箇所と別紙の決定欄を同時更新する。Bのモデル配置・起動内部・例外変換は本書で具体化し、全体ビルドやAの呼出し方法だけを合意対象とする。

第4週末でAI未統合ならPSD作業を止めAIのMUSTを優先する。第5週末で受入未達なら新機能を止め、両者で統合修正する。共有contractとビルド・依存変更は相互レビューを必須とする。

## 16 参照資料と要件対応

ページ番号は提供PDFの先頭を1として数える。本書の処理式、DTOの詳細、HTTPスキーマ、各制限・タイムアウトは、以下の方針を実装可能な形へ展開した提案設計である。

| 参照資料 | 使用箇所 |
| --- | --- |
| プログラミング言語_開発分担・進行計画案.pdf | p2～3 Bの責務と境界、p4～6 週次計画・結合・負荷再配分 |
| プログラミング言語_概要設計書.pdf | p6～10 モジュール・AI候補、p11～13 PSDと非同期、p17～19 詳細設計残項目と確定差分 |
| プログラミング言語_技術検証メモ.pdf | p4～7 ローカル接続・PSD・非同期、p10～12 技術スタックとPoC完了記録 |
| プログラミング言語_要求分析書_ver2.pdf | p2～5 基本要求と優先度、p6 最低完成フロー |
| プログラミング言語_要件分析書_ver2.pdf | p2～6 FR-05～36、p8～10 UI・非機能・技術要件と受入条件 |

### 要件と設計と試験の対応

| 要件 | 本書の章 | 主な試験 |
| --- | --- | --- |
| FR-05 / FR-06 / FR-07 | 3、4、13 | P01～03、P07、I01 |
| FR-11 / FR-12 | 3、6、13 | P05～07、I01 |
| FR-13 / FR-14 / FR-15 | 3、5、13 | P04、I01、I03 |
| FR-25 / FR-26 / FR-27 | 11、12 | S01～05、I05 |
| FR-29 / FR-30 | 7～9、13 | A01～03、A06～08、I02 |
| FR-31 / FR-32 / FR-34 | 7、13 Aとの共同責務 | I02 |
| FR-33 | 8～10、13 | A03～08、R01～02、I03～04 |
| FR-35 / FR-36 | 3、13 Aの履歴との連携 | I01～02 |
| UI-03 / UI-04 / NFR-02～08 | 3、8～13 | P07、A04～05、S03、I03～04 |
| NFR-09 / NFR-10 | 10、15 | macOS実機確認、ライセンスと配布物確認 |

### 実装時に残す記録

合意済みcontract版、API版、アルゴリズム版、依存ロック、モデル識別情報、サンプル入力と期待出力、単体・結合試験結果、計測端末とコールド／ウォーム条件を残す。ライブラリとモデルのライセンスは元資料の判定を出発点に、実際に同梱する版と重みについて確認し、READMEまたはThird-party licensesへ記載する。
