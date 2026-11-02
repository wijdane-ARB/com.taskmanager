package com.taskmanager.core.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        InputStream requestInputStream = request.getInputStream();
        // Limit max cached body size to 8KB
        byte[] buffer = new byte[8193];
        int bytesRead = requestInputStream.readNBytes(buffer, 0, buffer.length);
        if (bytesRead > 8192) {
            throw new IllegalArgumentException("Payload exceeds 8KB limit");
        }
        this.cachedBody = new byte[bytesRead];
        System.arraycopy(buffer, 0, this.cachedBody, 0, bytesRead);
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(this.cachedBody);
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    private static class CachedBodyServletInputStream extends ServletInputStream {
        private final InputStream cachedBodyInputStream;

        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public boolean isFinished() {
            try {
                return cachedBodyInputStream.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Not used in blocking I/O
        }

        @Override
        public int read() throws IOException {
            return cachedBodyInputStream.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return cachedBodyInputStream.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return cachedBodyInputStream.read(b, off, len);
        }
    }
}
