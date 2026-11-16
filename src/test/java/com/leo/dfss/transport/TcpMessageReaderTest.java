package com.leo.dfss.transport;

import com.leo.dfss.protocol.Message;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TcpMessageReaderTest {

    @Test
    void roundTrip_headerAndBody() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] body = {1, 2, 3, 4, 5};
        new TcpMessageWriter(buf).send(new Message("PING", "hello"), body);

        ReceivedMessage msg = new TcpMessageReader(new ByteArrayInputStream(buf.toByteArray())).read();

        assertEquals("PING", msg.getHeader().getType());
        assertEquals("hello", msg.getHeader().getData());
        assertArrayEquals(body, msg.getBody());
    }

    @Test
    void writer_refusesBodiesTheReaderWouldReject() {
        TcpMessageWriter writer = new TcpMessageWriter(new ByteArrayOutputStream());
        assertThrows(IOException.class,
                () -> writer.send(new Message("X", null), new byte[TcpMessageReader.MAX_BODY_BYTES + 1]));
    }

    @Test
    void endOfStream_returnsNull() throws Exception {
        assertNull(new TcpMessageReader(new ByteArrayInputStream(new byte[0])).read());
    }

    @Test
    void oversizedHeaderLength_isRejectedBeforeAllocating() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new DataOutputStream(buf).writeInt(Integer.MAX_VALUE); // would be a 2 GB allocation

        TcpMessageReader reader = new TcpMessageReader(new ByteArrayInputStream(buf.toByteArray()));
        assertThrows(IOException.class, reader::read);
    }

    @Test
    void oversizedDeclaredBody_isRejectedBeforeAllocating() throws Exception {
        String header = "{\"type\":\"CHUNK_UPLOAD\",\"bodyLength\":" + (TcpMessageReader.MAX_BODY_BYTES + 1) + "}";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeInt(headerBytes.length);
        out.write(headerBytes);

        TcpMessageReader reader = new TcpMessageReader(new ByteArrayInputStream(buf.toByteArray()));
        assertThrows(IOException.class, reader::read);
    }

    @Test
    void malformedHeaderJson_isAnIOException() throws Exception {
        byte[] headerBytes = "{not json".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeInt(headerBytes.length);
        out.write(headerBytes);

        TcpMessageReader reader = new TcpMessageReader(new ByteArrayInputStream(buf.toByteArray()));
        assertThrows(IOException.class, reader::read);
    }
}
