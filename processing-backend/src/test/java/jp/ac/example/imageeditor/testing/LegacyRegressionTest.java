package jp.ac.example.imageeditor.testing;

import org.junit.jupiter.api.Test;

/** Keep the independent pixel oracle and wire boundary suites during the public API migration. */
class LegacyRegressionTest {
    @Test
    void pixelRegression() throws Exception {
        RegressionTests.main(new String[0]);
    }

    @Test
    void wireAndProcessContracts() throws Exception {
        ContractTests.main(new String[0]);
    }
}
