package com.getindata.connectors.http.internal.table.lookup;

import java.io.File;
import java.util.List;
import java.util.Properties;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.formats.json.JsonFormatFactory;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableFactory.Context;
import org.apache.flink.table.runtime.connector.source.LookupRuntimeProviderContext;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.getindata.connectors.http.internal.HttpsConnectionTestBase;
import com.getindata.connectors.http.internal.config.HttpConnectorConfigConstants;
import static com.getindata.connectors.http.TestHelper.readTestFile;

@ExtendWith(MockitoExtension.class)
public class JavaNetHttpPollingClientHttpsConnectionTest extends HttpsConnectionTestBase {

    private static final String SAMPLES_FOLDER = "/http/";

    private static final List<String> LOOKUP_KEYS = List.of("id", "uuid");

    @Mock
    private Context dynamicTableFactoryContext;

    private DynamicTableSource.Context dynamicTableSourceContext;

    private JavaNetHttpPollingClientFactory pollingClientFactory;

    @BeforeEach
    public void setUp() {
        super.setUp();
        int[][] lookupKey = {{}};
        this.dynamicTableSourceContext = new LookupRuntimeProviderContext(lookupKey);
        this.pollingClientFactory = new JavaNetHttpPollingClientFactory();
    }

    @AfterEach
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testHttpsConnectionWithSelfSignedCert() {

        File keyStoreFile = new File(SERVER_KEYSTORE_PATH);

        wireMockServer = new WireMockServer(options()
            .httpsPort(HTTPS_SERVER_PORT)
            .httpDisabled(true)
            .keystorePath(keyStoreFile.getAbsolutePath())
            .keystorePassword("password")
            .keyManagerPassword("password")
        );

        wireMockServer.start();
        setupServerStub();

        properties.setProperty(HttpConnectorConfigConstants.ALLOW_SELF_SIGNED, "true");

        testPollingClientForHttpConnection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ca.crt", "server.crt"})
    public void testHttpsConnectionWithAddedCerts(String certName) {

        File keyStoreFile = new File(SERVER_KEYSTORE_PATH);
        File trustedCert = new File(CERTS_PATH + certName);

        wireMockServer = new WireMockServer(options()
            .httpsPort(HTTPS_SERVER_PORT)
            .httpDisabled(true)
            .keystorePath(keyStoreFile.getAbsolutePath())
            .keystorePassword("password")
            .keyManagerPassword("password")
        );

        wireMockServer.start();
        setupServerStub();

        properties.setProperty(
            HttpConnectorConfigConstants.SERVER_TRUSTED_CERT,
            trustedCert.getAbsolutePath()
        );

        testPollingClientForHttpConnection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"clientPrivateKey.pem", "clientPrivateKey.der"})
    public void testMTlsConnection(String clientPrivateKeyName) {

        File keyStoreFile = new File(SERVER_KEYSTORE_PATH);
        File trustStoreFile = new File(SERVER_TRUSTSTORE_PATH);
        File serverTrustedCert = new File(CERTS_PATH + "ca.crt");

        File clientCert = new File(CERTS_PATH + "client.crt");
        File clientPrivateKey = new File(CERTS_PATH + clientPrivateKeyName);

        this.wireMockServer = new WireMockServer(options()
            .httpDisabled(true)
            .httpsPort(HTTPS_SERVER_PORT)
            .keystorePath(keyStoreFile.getAbsolutePath())
            .keystorePassword("password")
            .keyManagerPassword("password")
            .needClientAuth(true)
            .trustStorePath(trustStoreFile.getAbsolutePath())
            .trustStorePassword("password")
        );

        wireMockServer.start();
        setupServerStub();

        properties.setProperty(
            HttpConnectorConfigConstants.SERVER_TRUSTED_CERT,
            serverTrustedCert.getAbsolutePath()
        );
        properties.setProperty(
            HttpConnectorConfigConstants.CLIENT_CERT,
            clientCert.getAbsolutePath()
        );
        properties.setProperty(
            HttpConnectorConfigConstants.CLIENT_PRIVATE_KEY,
            clientPrivateKey.getAbsolutePath()
        );

        testPollingClientForHttpConnection();
    }


    @ParameterizedTest
    @CsvSource(value = {
        "invalid.crt, client.crt, clientPrivateKey.pem",
        "ca.crt, invalid.crt, clientPrivateKey.pem",
        "ca.crt, client.crt, invalid.pem"
    })
    public void shouldThrowOnInvalidPath(
            String serverCertName,
            String clientCertName,
            String clientKeyName) {

        File serverTrustedCert = new File(CERTS_PATH + serverCertName);
        File clientCert = new File(CERTS_PATH + clientCertName);
        File clientPrivateKey = new File(CERTS_PATH + clientKeyName);

        properties.setProperty(
            HttpConnectorConfigConstants.SERVER_TRUSTED_CERT,
            serverTrustedCert.getAbsolutePath()
        );
        properties.setProperty(
            HttpConnectorConfigConstants.CLIENT_CERT,
            clientCert.getAbsolutePath()
        );
        properties.setProperty(
            HttpConnectorConfigConstants.CLIENT_PRIVATE_KEY,
            clientPrivateKey.getAbsolutePath()
        );

        assertThrows(RuntimeException.class, () -> setUpPollingClient(properties));
    }

    private void testPollingClientForHttpConnection() {
        JavaNetHttpPollingClient pollingClient = setUpPollingClient(properties);
        RowData result = pollingClient.pull(
            List.of(
                new LookupArg("id", "1"),
                new LookupArg("uuid", "2")
            )
        ).orElseThrow();

        assertResult(result);
    }

    private JavaNetHttpPollingClient setUpPollingClient(Properties properties) {

        HttpLookupConfig lookupConfig = HttpLookupConfig.builder()
            .url("https://localhost:" + HTTPS_SERVER_PORT + "/service")
            .arguments(LOOKUP_KEYS)
            .properties(properties)
            .build();

        DataType physicalDataType = DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.STRING()),
            DataTypes.FIELD("msg", DataTypes.STRING()),
            DataTypes.FIELD("uuid", DataTypes.STRING()),
            DataTypes.FIELD("details", DataTypes.ROW(
                DataTypes.FIELD("isActive", DataTypes.BOOLEAN()),
                DataTypes.FIELD("nestedDetails", DataTypes.ROW(
                    DataTypes.FIELD("balance", DataTypes.STRING())
                ))
            ))
        );

        DeserializationSchema<RowData> schemaDecoder =
            new JsonFormatFactory()
                .createDecodingFormat(dynamicTableFactoryContext, new Configuration())
                .createRuntimeDecoder(dynamicTableSourceContext, physicalDataType);

        return pollingClientFactory.createPollClient(lookupConfig, schemaDecoder);
    }

    private void setupServerStub() {
        wireMockServer.stubFor(
            get(urlEqualTo("/service?id=1&uuid=2"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withBody(readTestFile(SAMPLES_FOLDER + "HttpResult.json"))));
    }

    private void assertResult(RowData result) {

        assertThat(result.getArity()).isEqualTo(4);
        assertThat(result.getString(1)
            .toString()).isEqualTo("Returned HTTP message for parameter PARAM, COUNTER");

        RowData detailsRow = result.getRow(3, 2);
        assertThat(detailsRow.getBoolean(0)).isEqualTo(true);

        RowData nestedDetailsRow = detailsRow.getRow(1, 1);
        assertThat(nestedDetailsRow.getString(0).toString()).isEqualTo("$1,729.34");
    }

}