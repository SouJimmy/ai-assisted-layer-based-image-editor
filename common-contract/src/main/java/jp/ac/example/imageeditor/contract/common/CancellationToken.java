package jp.ac.example.imageeditor.contract.common;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancellationRequested();
}
