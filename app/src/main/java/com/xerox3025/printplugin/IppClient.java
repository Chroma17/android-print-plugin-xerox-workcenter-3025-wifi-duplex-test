package com.xerox3025.printplugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class IppClient {

    private static final String TAG = "IppClient";
    private static final int IPP_PORT = 631;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 60000;

    public static class IppResult {
        public final boolean success;
        public final int statusCode;
        public final String message;

        IppResult(boolean success, int statusCode, String message) {
            this.success = success;
            this.statusCode = statusCode;
            this.message = message;
        }
    }

    public static IppResult sendPrintJob(String printerIp, byte[] urfData, String jobName) {
        PrintLog.i(TAG, "Sending IPP Print-Job to " + printerIp + ":" + IPP_PORT
                + " (" + urfData.length + " bytes, job: " + jobName + ")");

        try {
            byte[] ippRequest = buildIppPrintJob(printerIp, urfData, jobName);

            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(printerIp, IPP_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String httpHeader = "POST /ipp/print HTTP/1.1\r\n"
                    + "Host: " + printerIp + "\r\n"
                    + "Content-Type: application/ipp\r\n"
                    + "Content-Length: " + ippRequest.length + "\r\n"
                    + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(httpHeader.getBytes());
            out.write(ippRequest);
            out.flush();

            // Read the full HTTP response. A single read() often returns only the HTTP
            // headers (the printer sends headers and body in separate segments), which
            // used to be reported as "IPP status 0xffffffff" even though the job printed.
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int headerEnd = -1;
            int contentLength = -1;
            boolean chunked = false;
            try {
                while (true) {
                    int n = in.read(chunk);
                    if (n < 0) break;
                    respBuf.write(chunk, 0, n);
                    byte[] cur = respBuf.toByteArray();
                    if (headerEnd < 0) {
                        headerEnd = indexOf(cur, cur.length, "\r\n\r\n");
                        if (headerEnd >= 0) {
                            String headers = new String(cur, 0, headerEnd).toLowerCase();
                            chunked = headers.contains("transfer-encoding: chunked");
                            int cl = headers.indexOf("content-length:");
                            if (cl >= 0) {
                                int eol = headers.indexOf("\r\n", cl);
                                String v = headers.substring(cl + 15, eol < 0 ? headers.length() : eol).trim();
                                try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                    if (headerEnd >= 0) {
                        int bodyLen = cur.length - (headerEnd + 4);
                        if (contentLength >= 0 ? bodyLen >= contentLength : bodyLen >= 12) break;
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                PrintLog.w(TAG, "Timed out waiting for printer response");
            }
            socket.close();

            byte[] resp = respBuf.toByteArray();
            int read = resp.length;
            if (read <= 0) {
                PrintLog.e(TAG, "Empty response from printer");
                return new IppResult(false, -1, "Empty response from printer");
            }

            String statusLine = new String(resp, 0, Math.min(read, 64)).split("\r\n", 2)[0];
            String[] parts = statusLine.split(" ");
            boolean httpOk = parts.length >= 2 && parts[1].equals("200");

            // Parse IPP status from response body: version(2) status(2) request-id(4)
            int ippStatus = -1;
            int bodyStart = indexOf(resp, read, "\r\n\r\n");
            if (bodyStart >= 0) {
                bodyStart += 4;
                if (chunked) {
                    int eol = indexOf(java.util.Arrays.copyOfRange(resp, bodyStart, read),
                            read - bodyStart, "\r\n");
                    bodyStart = (eol >= 0) ? bodyStart + eol + 2 : read;
                }
                if (bodyStart + 4 <= read) {
                    ippStatus = ((resp[bodyStart + 2] & 0xFF) << 8) | (resp[bodyStart + 3] & 0xFF);
                }
            }

            if (httpOk && ippStatus >= 0 && ippStatus < 0x0100) {
                PrintLog.i(TAG, "IPP job accepted (status 0x"
                        + String.format("%04x", ippStatus) + ")");
                return new IppResult(true, ippStatus, "Job accepted");
            } else if (httpOk && ippStatus < 0) {
                // The printer took the data (HTTP 200) but sent no readable IPP status.
                // In practice the job still prints, so don't report a failure.
                PrintLog.w(TAG, "HTTP 200 but no IPP status in response; assuming job accepted");
                return new IppResult(true, -1, "Job sent (no status from printer)");
            } else {
                String msg = "IPP error: HTTP " + (httpOk ? "200" : statusLine)
                        + ", IPP status 0x" + String.format("%04x", ippStatus);
                PrintLog.e(TAG, msg);
                return new IppResult(false, ippStatus, msg);
            }
        } catch (IOException e) {
            PrintLog.e(TAG, "IPP send failed: " + e.getMessage(), e);
            return new IppResult(false, -1, e.getMessage());
        }
    }

    private static int indexOf(byte[] data, int length, String pattern) {
        byte[] p = pattern.getBytes();
        for (int i = 0; i <= length - p.length; i++) {
            boolean match = true;
            for (int j = 0; j < p.length; j++) {
                if (data[i + j] != p[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    static byte[] buildIppPrintJob(String printerIp, byte[] documentData, String jobName) {
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        try {
            // IPP version 1.1
            req.write(new byte[]{0x01, 0x01});
            // Operation: Print-Job (0x0002)
            req.write(new byte[]{0x00, 0x02});
            // Request ID
            req.write(intToBytes(1));

            // Operation attributes tag
            req.write(0x01);
            writeIppString(req, (byte) 0x47, "attributes-charset", "utf-8");
            writeIppString(req, (byte) 0x48, "attributes-natural-language", "en");
            writeIppString(req, (byte) 0x45, "printer-uri",
                    "ipp://" + printerIp + "/ipp/print");
            writeIppString(req, (byte) 0x42, "requesting-user-name", "android-plugin");
            writeIppString(req, (byte) 0x42, "job-name", jobName);
            writeIppString(req, (byte) 0x49, "document-format", "image/urf");

            // End of attributes
            req.write(0x03);

            // Document data
            req.write(documentData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return req.toByteArray();
    }

    private static void writeIppString(ByteArrayOutputStream out, byte tag,
                                        String name, String value) throws IOException {
        out.write(tag);
        byte[] nameBytes = name.getBytes();
        out.write(shortToBytes(nameBytes.length));
        out.write(nameBytes);
        byte[] valueBytes = value.getBytes();
        out.write(shortToBytes(valueBytes.length));
        out.write(valueBytes);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value
        };
    }

    private static byte[] shortToBytes(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }
}
