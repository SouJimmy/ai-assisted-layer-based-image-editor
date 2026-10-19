# 外部ライブラリとモデル

Java部分はJDK 21とJackson 2.18.12を使用し、試験はJUnit 5.11.4で実行します。依存版はgradle/libs.versions.tomlへ固定しています。Pythonの正確な依存版とMobileSAMのコミットは[requirements.lock](python-service/requirements.lock)を参照してください。モデル重みはGit対象外で、[manifest](python-service/models/model-manifest.json)に取得元とSHA-256を記録しています。

| 主な依存 | 上流 | ライセンス |
| --- | --- | --- |
| Jackson | https://github.com/FasterXML/jackson | Apache-2.0 |
| JUnit 5（試験用） | https://github.com/junit-team/junit5 | EPL-2.0 |
| MobileSAMと公式重み | https://github.com/ChaoningZhang/MobileSAM | Apache-2.0 |
| PyTorch / torchvision | https://github.com/pytorch/pytorch / https://github.com/pytorch/vision | PyTorchはApache/BSD/MIT等の複合表記、torchvisionはBSD。各distributionのライセンス一式を参照 |
| timm | https://github.com/huggingface/pytorch-image-models | Apache-2.0 |
| psd-tools | https://github.com/psd-tools/psd-tools | MIT |
| FastAPI | https://github.com/fastapi/fastapi | MIT |
| Uvicorn | https://github.com/Kludex/uvicorn | BSD-3-Clause |
| Pillow | https://github.com/python-pillow/Pillow | MIT-CMU |
| NumPy | https://github.com/numpy/numpy | BSD-3-Clause等の複合表記。distributionのライセンス一式を参照 |

Pythonライブラリは仮想環境、JavaライブラリはGradleで解決します。仮想環境・重みを含む配布物を作る際は、間接依存を含めてインストール済みdistributionのLICENSE・NOTICEを同梱してください。この一覧だけで配布時のライセンス文書の同梱を代替するものではありません。

## 評価用素材と間接依存

評価用写真と派生した候補画像の出典・利用条件は[評価記録](docs/validation/README.md)を参照してください。原画像のURLとSHA-256は`tools/validation-images.json`に記録しています。通常テストやサービスの実行に評価用写真は不要です。

完全ロックのhuggingface_hub 2.2.0が要求するHTTPX2 / HTTPCore2も2.13.1に固定しています。[Pydanticの上流](https://github.com/pydantic/httpx2)と各distributionのライセンスを、配布時の間接依存一覧へ含めてください。
