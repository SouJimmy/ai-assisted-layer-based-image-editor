package jp.ac.example.imageeditor.integration.runtime;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;

import java.util.*;

public record ServiceStatus(
        ServiceState state,
        Optional<String> serviceInstanceId,
        boolean aiReady,
        boolean psdReady,
        Optional<DeviceKind> device,
        Optional<ServiceError> aiError,
        Optional<ServiceError> lifecycleError) {}
