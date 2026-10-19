package jp.ac.example.imageeditor.contract.ai;

import java.awt.image.BufferedImage;

public record MaskCandidate(String candidateId, BufferedImage mask, double score) {}
