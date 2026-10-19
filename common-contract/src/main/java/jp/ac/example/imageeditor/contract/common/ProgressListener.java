package jp.ac.example.imageeditor.contract.common;

@FunctionalInterface
public interface ProgressListener {
    void onProgress(double fraction);
}
