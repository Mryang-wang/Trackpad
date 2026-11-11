package local.remotevox.trackpadtest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TrackpadProtocol {
    static final UUID SERVICE = UUID.fromString("E2A91F01-AB0D-4A69-A4B3-CA4FA1B0E492");
    static final UUID CHALLENGE = UUID.fromString("E2A91F02-AB0D-4A69-A4B3-CA4FA1B0E492");
    static final UUID COMMAND = UUID.fromString("E2A91F03-AB0D-4A69-A4B3-CA4FA1B0E492");
    static final UUID CONSOLE_REPLY = UUID.fromString("E2A91F04-AB0D-4A69-A4B3-CA4FA1B0E492");
    static final byte AUTH = 0, MOVE = 1, SCROLL = 2, LEFT_CLICK = 3, RIGHT_CLICK = 4, HEARTBEAT = 5,
                      DRAG = 6, END_DRAG = 7, SWITCH_SPACE = 8;

    static final byte LIST_APPS = 16, OPEN_APP = 17, QUIT_APP = 18,
                      CODEX_DICTATION = 19, CODEX_SEND = 20, CODEX_STOP = 21, CODEX_NEW = 22, APP_ICON = 23, ICON_CHUNK = 24, APP_STATE = 25, CATALOG_STATUS = 26, CATALOG_STATE = 27;

    static boolean isConsole(byte kind) {
        return kind >= LIST_APPS && kind <= CATALOG_STATE;
    }

    private final Mac mac;
    private final byte[] nonce;

    TrackpadProtocol(String code, byte[] nonce) {
        if (nonce.length != 16) throw new IllegalArgumentException("Invalid challenge length");
        this.nonce = nonce.clone();
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    byte[] packet(byte kind, int sequence, int x, int y) {
        ByteBuffer packet = ByteBuffer.allocate(17);
        packet.put(kind).putInt(sequence).putShort((short) x).putShort((short) y);
        mac.update(nonce);
        mac.update(packet.array(), 0, packet.position());
        packet.put(mac.doFinal(), 0, 8);
        return packet.array();
    }
}
