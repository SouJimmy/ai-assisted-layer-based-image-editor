package jp.ac.example.imageeditor.integration.runtime;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;

import java.util.*;

public final class ServiceLifecycleException extends BackendException {
    public ServiceLifecycleException(ErrorCode code, String message, Throwable cause) {
        super(code, message, null, 0, false, cause);
    }
}
