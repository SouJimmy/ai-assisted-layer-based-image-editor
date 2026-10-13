# 外部ライブラリとモデル

Java部分はJDK 17の標準APIで構成しています。Pythonの正確な依存版とMobileSAMのコミットは[requirements.lock](python-service/requirements.lock)を参照してください。モデル重みはGit対象外で、[manifest](python-service/models/model-manifest.json)に取得元とSHA-256を記録しています。

| 主な依存 | 上流 | ライセンス |
| --- | --- | --- |
| MobileSAMと公式重み | https://github.com/ChaoningZhang/MobileSAM | Apache-2.0 |
| PyTorch / torchvision | https://github.com/pytorch/pytorch / https://github.com/pytorch/vision | PyTorchはApache/BSD/MIT等の複合表記、torchvisionはBSD。各distributionのライセンス一式を参照 |
| timm | https://github.com/huggingface/pytorch-image-models | Apache-2.0 |
| psd-tools | https://github.com/psd-tools/psd-tools | MIT |
| FastAPI | https://github.com/fastapi/fastapi | MIT |
| Uvicorn | https://github.com/Kludex/uvicorn | BSD-3-Clause |
| Pillow | https://github.com/python-pillow/Pillow | MIT-CMU |
| NumPy | https://github.com/numpy/numpy | BSD-3-Clause等の複合表記。distributionのライセンス一式を参照 |

各ライブラリは仮想環境へインストールします。仮想環境・重みを含む配布物を作る際は、間接依存を含めてインストール済みdistributionのLICENSE・NOTICEを同梱してください。この一覧だけで配布時のライセンス文書の同梱を代替するものではありません。
