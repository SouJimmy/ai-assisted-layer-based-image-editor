package jp.ac.example.imageeditor.integration.runtime;

import jp.ac.example.imageeditor.contract.ai.*;
import jp.ac.example.imageeditor.contract.common.*;

import java.util.*;

public enum ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED,
    STOPPING,
    CLOSED
}
