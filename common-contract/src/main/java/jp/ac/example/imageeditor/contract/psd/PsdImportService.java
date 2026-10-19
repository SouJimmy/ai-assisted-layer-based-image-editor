package jp.ac.example.imageeditor.contract.psd;

import jp.ac.example.imageeditor.contract.common.AsyncOperation;

public interface PsdImportService {
    AsyncOperation<PsdImportResult> importPsd(java.nio.file.Path file);
}
