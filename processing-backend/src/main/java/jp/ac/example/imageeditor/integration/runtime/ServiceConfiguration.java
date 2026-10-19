package jp.ac.example.imageeditor.integration.runtime;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;

import java.nio.file.Path;
import java.util.*;

public record ServiceConfiguration(
        Path pythonExecutable, Path serviceDirectory, Path modelManifest, Path logDirectory) {
    public ServiceConfiguration {
        pythonExecutable = pythonExecutable.toAbsolutePath().normalize();
        serviceDirectory = serviceDirectory.toAbsolutePath().normalize();
        modelManifest = modelManifest.toAbsolutePath().normalize();
        logDirectory = logDirectory.toAbsolutePath().normalize();
    }
}
