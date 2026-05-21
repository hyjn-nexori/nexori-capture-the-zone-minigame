package io.github.hyjn.nexoridemo.midcapture;

public interface MidCaptureIntegrationHandle extends AutoCloseable {

    boolean active();

    @Override
    void close();

    static MidCaptureIntegrationHandle inactive() {
        return new MidCaptureIntegrationHandle() {
            @Override
            public boolean active() {
                return false;
            }

            @Override
            public void close() {
            }
        };
    }
}
