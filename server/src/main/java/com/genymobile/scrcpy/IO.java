package com.genymobile.scrcpy;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public final class IO {
    private IO() {
        // not instantiable
    }

//    public static void writeFully(FileDescriptor fd, ByteBuffer from) throws IOException {
    public synchronized static void writeFully(SocketChannel channel, ByteBuffer from) throws IOException {
        // ByteBuffer position is not updated as expected by Os.write() on old Android versions, so
        // count the remaining bytes manually.
        // See <https://github.com/Genymobile/scrcpy/issues/291>.
        int remaining = from.remaining();
        while (remaining > 0) {
            try {
//                int w = Os.write(fd, from);
                int w = channel.write(from);
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
//                    throw new AssertionError("Os.write() returned a negative value (" + w + ")");
                    throw new AssertionError("write() returned a negative value (" + w + ")");
                }
                remaining -= w;
            } catch (Exception e) {
                throw new IOException(e);
//            } catch (ErrnoException e) {
//                if (e.errno != OsConstants.EINTR) {
//                    throw new IOException(e);
//                }
            }
        }
    }

//    public static void writeFully(FileDescriptor fd, byte[] buffer, int offset, int len) throws IOException {
//        writeFully(fd, ByteBuffer.wrap(buffer, offset, len));
//    }
    public synchronized static void writeFully(SocketChannel channel, byte[] buffer, int offset, int len) throws IOException {
        writeFully(channel, ByteBuffer.wrap(buffer, offset, len));
    }
}
