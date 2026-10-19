package jp.ac.example.imageeditor.contract.processing;

import jp.ac.example.imageeditor.contract.common.AsyncOperation;
import jp.ac.example.imageeditor.contract.common.ProgressListener;

public interface ImageProcessor {
    ProcessingResult process(ProcessingRequest request, ProcessingContext context)
            throws ProcessingException;

    AsyncOperation<ProcessingResult> submit(ProcessingRequest request, ProgressListener progress);
}
