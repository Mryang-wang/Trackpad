package local.remotevox.trackpadtest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TrackpadProtocol {
    static final UUID SERVICE = UUID.fromString("C850C1C5-75C8-4D87-A4A5-804AB81FE61F");
    static final UUID CHALLENGE = UUID.fromString("C850C1C6-75C8-4D87-A4A5-804AB81FE61F");
    static final UUID COMMAND = UUID.fromString("C850C1C7-75C8-4D87-A4A5-804AB81FE61F");
    static final byte AUTH = 0, MOVE = 1, SCROLL = 2, LEFT_CLICK = 3, RIGHT_CLICK = 4, HEARTBEAT = 5,
                      DRAG = 6, END_DRAG = 7, SWITCH_SPACE = 8;

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
