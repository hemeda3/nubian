package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxSession;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirecrackerSandboxDisplay implements SandboxDisplay {
    private static final Logger log = LoggerFactory.getLogger(FirecrackerSandboxDisplay.class);
    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;

    public FirecrackerSandboxDisplay(FirecrackerSandboxSessionService sessions) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions);
    }

    public FirecrackerSandboxDisplay(String providerId, FirecrackerSandboxSessionService sessions) {
        this.providerId = providerId;
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("runtime", "firecracker", "implementation", "flyvm-screenshot-api");
    }

    @Override
    public CompletableFuture<SandboxDisplayFrame> captureFrame(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            SandboxSession session = sessions.requireRunningSession(sessionId, "display.captureFrame").orElseThrow();
            String baseUrl = session.metadata().get("flyvm.agentBaseUrl");
            byte[] data;
            Map<String, String> metadata = new LinkedHashMap<>(session.metadata());
            try {
                data = sessions.flyVmClient().screenshot(baseUrl);
                metadata.put("display.source", "flyvm-api:/v1/computers/{vmId}/screenshot");
            } catch (RuntimeException ex) {
                data = captureViaDirectVnc(session);
                metadata.put("display.source", "direct-vnc:" + directVncHost(session) + ":" + directVncPort(session));
                metadata.put("display.fallbackReason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            dimensionsFromPng(data).forEach(metadata::putIfAbsent);
            return new SandboxDisplayFrame(
                    providerId,
                    sessionId,
                    parseInt(metadata.get("display.width"), 0),
                    parseInt(metadata.get("display.height"), 0),
                    "image/png",
                    data,
                    Instant.now(),
                    metadata);
        });
    }

    @Override
    public CompletableFuture<Void> resizeDisplay(String sessionId, int width, int height) {
        if (width <= 0 || height <= 0) {
            return FirecrackerSandboxFailures.failedFuture(new SandboxFailure(
                    providerId,
                    sessionId,
                    SandboxFailureCode.VALIDATION_ERROR,
                    "width and height must be positive",
                    "display.resizeDisplay",
                    false,
                    Map.of()));
        }
        return CompletableFuture.runAsync(() -> {
            SandboxSession session = sessions.requireRunningSession(sessionId, "display.resizeDisplay").orElseThrow();
            SandboxCommandResult result = sessions.flyVmClient().execute(
                    sessionId,
                    session.metadata().get("flyvm.agentBaseUrl"),
                    new SandboxCommand(
                            "xrandr --output default --mode " + width + "x" + height,
                            List.of(),
                            "/workspace",
                            Map.of(),
                            Duration.ofSeconds(20),
                            false,
                            Map.of("sandbox.operation", "display.resizeDisplay")));
            if (!result.successful()) {
                throw new FirecrackerSandboxException(new SandboxFailure(
                        providerId,
                        sessionId,
                        SandboxFailureCode.DISPLAY_ERROR,
                        result.stderr().isBlank() ? "FlyVM display resize failed" : result.stderr(),
                        "display.resizeDisplay",
                        true,
                        result.metadata()));
            }
        });
    }

    private Map<String, String> dimensions(String sessionId, String baseUrl) {
        SandboxCommandResult result = sessions.flyVmClient().execute(
                sessionId,
                baseUrl,
                new SandboxCommand(
                        "if command -v xdotool >/dev/null 2>&1; then xdotool getdisplaygeometry; fi",
                        List.of(),
                        "/workspace",
                        Map.of(),
                        Duration.ofSeconds(10),
                        false,
                        Map.of("sandbox.operation", "display.captureFrame.geometry")));
        if (!result.successful()) {
            return Map.of();
        }
        String[] size = result.stdout().trim().split("\\s+");
        if (size.length < 2) {
            return Map.of();
        }
        return Map.of("display.width", size[0], "display.height", size[1]);
    }

    private byte[] captureViaDirectVnc(SandboxSession session) {
        String host = directVncHost(session);
        int port = directVncPort(session);
        if (host.isBlank() || port <= 0) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    providerId,
                    session.sessionId(),
                    SandboxFailureCode.DISPLAY_ERROR,
                    "No direct VNC endpoint is configured for screenshot fallback",
                    "display.captureFrame.directVnc",
                    true,
                    session.metadata()));
        }
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            byte[] version = in.readNBytes(12);
            if (version.length != 12 || !new String(version, StandardCharsets.US_ASCII).startsWith("RFB ")) {
                throw new IllegalStateException("invalid RFB server version");
            }
            out.write(version);
            out.flush();

            int typeCount = in.readUnsignedByte();
            if (typeCount == 0) {
                throw new IllegalStateException("VNC server rejected security negotiation: " + in.readInt());
            }
            boolean noneSupported = false;
            for (int i = 0; i < typeCount; i++) {
                noneSupported |= in.readUnsignedByte() == 1;
            }
            if (!noneSupported) {
                throw new IllegalStateException("VNC server does not support no-auth security");
            }
            out.writeByte(1);
            out.flush();
            int securityResult = in.readInt();
            if (securityResult != 0) {
                throw new IllegalStateException("VNC security negotiation failed: " + securityResult);
            }

            out.writeByte(1); // shared desktop
            out.flush();
            int width = in.readUnsignedShort();
            int height = in.readUnsignedShort();
            in.skipNBytes(16);
            int nameLength = in.readInt();
            if (nameLength > 0) {
                in.skipNBytes(nameLength);
            }

            setPixelFormat(out);
            setRawEncoding(out);
            framebufferUpdateRequest(out, width, height);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline) {
                int messageType = in.readUnsignedByte();
                if (messageType == 0) {
                    in.readUnsignedByte(); // padding
                    int rectangles = in.readUnsignedShort();
                    for (int i = 0; i < rectangles; i++) {
                        readRectangle(in, image);
                    }
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", png);
                    return png.toByteArray();
                }
                if (messageType == 2) {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    continue;
                }
                throw new IllegalStateException("unsupported VNC server message type: " + messageType);
            }
            throw new IllegalStateException("timed out waiting for VNC framebuffer update");
        } catch (Exception ex) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    providerId,
                    session.sessionId(),
                    SandboxFailureCode.DISPLAY_ERROR,
                    "Direct VNC screenshot failed: " + ex.getMessage(),
                    "display.captureFrame.directVnc",
                    true,
                    Map.of("host", host, "port", Integer.toString(port))));
        }
    }

    private static void setPixelFormat(DataOutputStream out) throws java.io.IOException {
        out.writeByte(0);
        out.write(new byte[] {0, 0, 0});
        out.writeByte(32);
        out.writeByte(24);
        out.writeByte(0); // little endian
        out.writeByte(1); // true color
        out.writeShort(255);
        out.writeShort(255);
        out.writeShort(255);
        out.writeByte(16);
        out.writeByte(8);
        out.writeByte(0);
        out.write(new byte[] {0, 0, 0});
        out.flush();
    }

    private static void setRawEncoding(DataOutputStream out) throws java.io.IOException {
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(1);
        out.writeInt(0);
        out.flush();
    }

    private static void framebufferUpdateRequest(DataOutputStream out, int width, int height) throws java.io.IOException {
        out.writeByte(3);
        out.writeByte(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(width);
        out.writeShort(height);
        out.flush();
    }

    private static void readRectangle(DataInputStream in, BufferedImage image) throws java.io.IOException {
        int x = in.readUnsignedShort();
        int y = in.readUnsignedShort();
        int width = in.readUnsignedShort();
        int height = in.readUnsignedShort();
        int encoding = in.readInt();
        if (encoding != 0) {
            throw new IllegalStateException("unsupported VNC encoding: " + encoding);
        }
        byte[] pixels = in.readNBytes(width * height * 4);
        int offset = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int pixel = (pixels[offset] & 0xff)
                        | ((pixels[offset + 1] & 0xff) << 8)
                        | ((pixels[offset + 2] & 0xff) << 16)
                        | ((pixels[offset + 3] & 0xff) << 24);
                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = pixel & 0xff;
                int targetX = x + col;
                int targetY = y + row;
                if (targetX < image.getWidth() && targetY < image.getHeight()) {
                    image.setRGB(targetX, targetY, (red << 16) | (green << 8) | blue);
                }
                offset += 4;
            }
        }
    }

    private Map<String, String> dimensionsFromPng(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) {
                return Map.of();
            }
            return Map.of(
                    "display.width", Integer.toString(image.getWidth()),
                    "display.height", Integer.toString(image.getHeight()));
        } catch (Exception ex) {
            log.debug("dimensionsFromPng fallback: {}", ex.toString());
            return Map.of();
        }
    }

    private String directVncHost(SandboxSession session) {
        String noVncUrl = session.metadata().getOrDefault("flyvm.noVncUrl", "");
        try {
            URI uri = URI.create(noVncUrl);
            String queryHost = queryParam(uri.getRawQuery(), "host");
            if (queryHost != null && !queryHost.isBlank()) {
                return queryHost;
            }
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception ex) {
            log.debug("directVncHost fallback: {}", ex.toString());
            return "";
        }
    }

    private int directVncPort(SandboxSession session) {
        return parseInt(session.metadata().get("flyvm.vncPort"), 5900);
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            if (key.equals(name)) {
                return equals < 0 ? "" : java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (RuntimeException ex) {
            log.debug("parseInt fallback: {}", ex.toString());
            return fallback;
        }
    }
}
