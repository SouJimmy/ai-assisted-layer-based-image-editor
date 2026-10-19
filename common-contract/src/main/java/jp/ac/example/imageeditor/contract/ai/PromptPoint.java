package jp.ac.example.imageeditor.contract.ai;

import java.util.Objects;

public record PromptPoint(int x, int y, PromptLabel label) {
    public PromptPoint {
        Objects.requireNonNull(label);
    }
}
