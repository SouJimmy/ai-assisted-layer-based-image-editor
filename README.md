# AI Assisted Layer Based Image Editor

JavaとJavaFXによるデスクトップ画像編集ツールです。基本画像編集、レイヤー編集、AIによる対象物の切り抜きと新規レイヤーへの分離を目的とします。AIとPSD解析は、端末内のPythonサービスで実行します。

## 開発状況

Bの画像処理・AI・PSDを共通contractへ接続しました。Java 21 / Gradle Wrapper 8.10.2、Python 3.11.16へ移行し、取消可能ハンドル、checked例外、型付きPSD DTO、サービス再起動と最終終了を実装しています。

AのJavaFX画面・Document/History/保存のソースは本リポジトリにまだありません。共通APIから実モデルまでのB単独結合は検証し、Aの画面を通す受入は別途行います。[移行実装記録](docs/contract-migration.md)を参照してください。Aとの共通仕様の最終相互レビューは完了扱いにしていません。

## すぐに試す

JDK 21とPython 3.11を用意してください。

```sh
./gradlew checkBackend
./gradlew :processing-backend:cli -Pinput=input.png -Poutput=gray.png -Poperation=GRAYSCALE
```

AI・PSDの依存導入、モデル取得、起動・試験の手順は[開発と使い方](docs/usage.md)に記載しています。

## ドキュメント

2026年10月19日に担当Aの詳細設計と回答を受領しました。[移行・結合計画](docs/coordination/担当A回答を踏まえた移行・結合計画.md)に、現実装との差分と次の実施順をまとめています。B詳細設計を改訂し、[共通contract設計書](docs/design/共通contract設計書.md)で具体型・署名・取消等を定義しました。Java 21・共通contract・Python 3.11へのB単独移行を実施しました。

| ドキュメント | 内容 |
| --- | --- |
| [担当A回答を踏まえた移行・結合計画](docs/coordination/担当A回答を踏まえた移行・結合計画.md) | A回答との差分、環境移行、共通API、初回結合と受入の順序 |
| [共通contract設計書](docs/design/共通contract設計書.md) | 公開API、型付きDTO、取消・checked例外、サービス寿命の具体案 |
| [共通contract具体案の確認事項](docs/coordination/共通contract具体案の確認事項.md) | A回答にない追加具体案と、Aへ確認する残りの項目 |
| [担当B 詳細設計書](docs/design/担当B_詳細設計書.md) | 画像処理、AI通信、Pythonサービス、PSD取り込み、異常系、テスト仕様 |
| [担当Aへの仕様確認と結合の相談](docs/coordination/担当Aとの合意事項.md) | 担当Aに確認する9項目、Bの提案、回答欄 |
| [開発と使い方](docs/usage.md) | 環境構築、ビルド、画像加工、サービス起動、テスト |
| [移行実装記録](docs/contract-migration.md) | 共通API・環境移行、検証結果、残る共同作業 |
| [実装状況](docs/implementation.md) | 実装済み範囲、設計との差分、結合待ちの項目 |
| [担当Bの完了状況](docs/b-completion-audit.md) | 未実装・合意待ち・品質や性能の評価待ち |
| [コードの可読性とテストの確認](docs/code-review.md) | 読みやすさの改善、重複テストの整理、コードの案内 |
| [性能・品質・環境再現の評価](docs/validation/README.md) | 2K・4K、実写真、PSD9ケース、300回反復、クリーン環境の結果 |
| [PSD単体とMPSの資源評価](docs/validation/resources-2026-10-20/README.md) | 独立プロセスのPSD最大RSSと、実モデル100回のRSS・MPSメモリ・解放確認 |
| [カバレッジ報告](docs/coverage.md) | 行・分岐カバレッジの改善結果と再現手順 |
| [外部ライブラリとモデル](THIRD_PARTY.md) | 依存とライセンス、モデル取得元 |
| [元資料一覧](docs/references/README.md) | 要求・要件・概要設計・技術検証・分担計画のPDF |

## 構成

```text
ai-assisted-layer-based-image-editor/
├── common-contract/     # JavaFX/Jackson非依存の公開APIとDTO
├── processing-backend/  # Bの演算・HTTP・サービス寿命・CLI・JUnit
├── editor-app/          # Aのソース受領・結合先（現在は配置枠のみ）
├── python-service/      # AI・PSDローカルサービス
├── gradle/              # WrapperとVersion Catalog
├── gradlew
├── settings.gradle.kts
├── tools/               # Gradle呼出し・計測・環境検証の補助
└── docs/                # 設計、使い方、評価、元PDF
```

## プロジェクト全体の開発範囲

- PNG/JPEGの読み込みと書き出し
- トリミング、90度回転、反転、画素数の変更
- 明るさ・コントラスト・彩度、基本フィルター、ぼかし、モザイク
- 文字と自由描画、レイヤーの作成・削除・移動・表示・順序・透明度の編集
- MobileSAMによる対象物選択、最大3候補の確認、正負クリックによる補正
- 透明背景の新規レイヤーへの分離とUndo/Redo

PSD取り込みと`.jimg`プロジェクト保存はSHOULD機能です。PSD完全互換・PSD書き出し・AI画像生成はVersion 1の対象外です。上記は開発範囲であり、実装済み機能の一覧ではありません。

## 担当範囲

| 担当 | 主な責務 |
| --- | --- |
| A | JavaFX画面、DocumentとLayer、ツール入力、履歴、通常画像入出力、プロジェクト保存、最終統合 |
| B | Javaの画像処理、AIのHTTPクライアント、Pythonサービス、MobileSAM、PSD解析と中間DTO、非同期処理の補助 |

Bは処理結果を返し、Aが編集モデル・レイヤー・履歴へ反映します。共通インターフェースとビルド設定・依存ライブラリの変更は相互レビューします。

## 開発を進めるには

1. [開発と使い方](docs/usage.md)に沿ってビルドとテストを実行します。
2. [担当Aへの確認事項](docs/coordination/担当Aとの合意事項.md)で共通境界を固定し、画面・編集モデルと接続します。
3. 変更に対応するテストとドキュメントを同時に更新します。
4. AIとPSDのエラー時にも、既存の編集状態を変更しないことを結合で確認します。

モデル重み、仮想環境、ビルド成果物、個人の編集画像はGitへ登録しません。依存の正確な版は`python-service/requirements.lock`、モデル取得元とSHA-256は`python-service/models/model-manifest.json`へ記録しています。
