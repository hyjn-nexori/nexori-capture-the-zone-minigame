package io.github.hyjn.nexoridemo.midcapture;

public interface MidCaptureListenerRegistration extends AutoCloseable {

    @Override
    void close();
}
