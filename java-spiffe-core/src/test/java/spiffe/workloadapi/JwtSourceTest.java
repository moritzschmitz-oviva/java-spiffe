package spiffe.workloadapi;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spiffe.bundle.jwtbundle.JwtBundle;
import spiffe.exception.BundleNotFoundException;
import spiffe.exception.JwtSourceException;
import spiffe.exception.JwtSvidException;
import spiffe.exception.SocketEndpointAddressException;
import spiffe.spiffeid.SpiffeId;
import spiffe.spiffeid.TrustDomain;
import spiffe.svid.jwtsvid.JwtSvid;
import spiffe.workloadapi.internal.ManagedChannelWrapper;
import spiffe.workloadapi.internal.SecurityHeaderInterceptor;
import spiffe.workloadapi.internal.SpiffeWorkloadAPIGrpc;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class JwtSourceTest {

    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private JwtSource jwtSource;

    @BeforeEach
    void setUp() throws IOException, JwtSourceException, SocketEndpointAddressException {
        // Generate a unique in-process server name.
        String serverName = InProcessServerBuilder.generateName();

        // Create a server, add service, start, and register for automatic graceful shutdown.
        FakeWorkloadApi fakeWorkloadApi = new FakeWorkloadApi();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor().addService(fakeWorkloadApi).build().start();
        grpcCleanup.register(server);

        // Create WorkloadApiClient using Stubs that will connect to the fake WorkloadApiService.
        ManagedChannel inProcessChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        grpcCleanup.register(inProcessChannel);

        SpiffeWorkloadAPIGrpc.SpiffeWorkloadAPIBlockingStub workloadApiBlockingStub = SpiffeWorkloadAPIGrpc
                .newBlockingStub(inProcessChannel)
                .withInterceptors(new SecurityHeaderInterceptor());

        SpiffeWorkloadAPIGrpc.SpiffeWorkloadAPIStub workloadAPIStub = SpiffeWorkloadAPIGrpc
                .newStub(inProcessChannel)
                .withInterceptors(new SecurityHeaderInterceptor());

        WorkloadApiClient workloadApiClient = new WorkloadApiClient(workloadAPIStub, workloadApiBlockingStub, new ManagedChannelWrapper(inProcessChannel));

        JwtSource.JwtSourceOptions options = JwtSource.JwtSourceOptions.builder().workloadApiClient(workloadApiClient).build();
        jwtSource = JwtSource.newSource(options);
    }

    @AfterEach
    void tearDown() {
        jwtSource.close();
    }

    @Test
    void testGetJwtBundleForTrustDomain() {
        try {
            JwtBundle bundle = jwtSource.getJwtBundleForTrustDomain(TrustDomain.of("example.org"));
            assertNotNull(bundle);
            assertEquals(TrustDomain.of("example.org"), bundle.getTrustDomain());
        } catch (BundleNotFoundException e) {
            fail(e);
        }
    }

    @Test
    void testGetJwtBundleForTrustDomain_SourceIsClosed_ThrowsIllegalStateException() {
        jwtSource.close();
        try {
            jwtSource.getJwtBundleForTrustDomain(TrustDomain.of("example.org"));
            fail("expected exception");
        } catch (IllegalStateException e) {
            assertEquals("JWT bundle source is closed", e.getMessage());
        } catch (BundleNotFoundException e) {
            fail("not expected exception", e);
        }
    }

    @Test
    void testFetchJwtSvid() {
        try {
            JwtSvid svid = jwtSource.fetchJwtSvid(SpiffeId.parse("spiffe://example.org/workload-server"), "aud1", "aud2", "aud3");
            assertNotNull(svid);
            assertEquals(SpiffeId.parse("spiffe://example.org/workload-server"), svid.getSpiffeId());
            assertEquals(Arrays.asList("aud1", "aud2", "aud3"), svid.getAudience());
        } catch (JwtSvidException e) {
            fail(e);
        }
    }

    @Test
    void testFetchJwtSvid_SourceIsClosed_ThrowsIllegalStateException() {
        jwtSource.close();
        try {
            jwtSource.fetchJwtSvid(SpiffeId.parse("spiffe://example.org/workload-server"), "aud1", "aud2", "aud3");
            fail("expected exception");
        } catch (IllegalStateException e) {
            assertEquals("JWT SVID source is closed", e.getMessage());
        } catch (JwtSvidException e) {
            fail(e);
        }
    }
}