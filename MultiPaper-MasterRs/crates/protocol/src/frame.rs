//! Length-prefixed framing matching the Java `MessageLengthEncoder` /
//! `MessageLengthDecoder` plus `MessageEncoder` / `MessageDecoder`.
//!
//! Wire layout of a single frame:
//!
//! ```text
//! varint(body_len)
//! varint(transaction_id)
//! varint(message_id)
//! <body_len - varint_len(transaction_id) - varint_len(message_id) bytes of body>
//! ```
//!
//! The outer `body_len` is the byte count of everything that follows it on
//! the wire (the transaction id, the message id, and the body bytes).

use crate::{
    codec::{ReadExt, WriteExt},
    error::{ProtocolError, ProtocolResult},
    varint,
};
use bytes::{Buf, BufMut, Bytes, BytesMut};

/// Soft cap on frame size. The Java side has no explicit cap but in practice
/// nothing legitimate exceeds this; rejecting larger frames protects the
/// master from a runaway client allocating multi-GB buffers.
pub const MAX_FRAME_SIZE: usize = 64 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct Frame {
    pub transaction_id: u32,
    pub message_id: u32,
    pub body: Bytes,
}

/// Attempt to decode one frame from `buf`. Returns `Ok(None)` if more bytes
/// are needed (caller should read more from the socket and retry). Advances
/// `buf` past the consumed bytes on success.
pub fn try_decode_frame(buf: &mut BytesMut) -> ProtocolResult<Option<Frame>> {
    // Peek the varint length without committing the read.
    let mut peek = &buf[..];
    let start_len = peek.len();
    let body_len = match varint::read_varint(&mut peek) {
        Ok(v) => v as usize,
        Err(ProtocolError::UnexpectedEof { .. }) => return Ok(None),
        Err(e) => return Err(e),
    };
    let header_len = start_len - peek.len();

    if body_len > MAX_FRAME_SIZE {
        return Err(ProtocolError::FrameTooLarge {
            size: body_len,
            max: MAX_FRAME_SIZE,
        });
    }

    if buf.len() < header_len + body_len {
        return Ok(None);
    }

    // Commit: skip the length varint, slice off the body.
    buf.advance(header_len);
    let mut body = buf.split_to(body_len);
    let transaction_id = body.read_varint()? as u32;
    let message_id = body.read_varint()? as u32;

    Ok(Some(Frame {
        transaction_id,
        message_id,
        body: body.freeze(),
    }))
}

/// Encode a frame, returning the bytes to write to the socket.
pub fn encode_frame<F>(transaction_id: u32, message_id: u32, write_body: F) -> Bytes
where
    F: FnOnce(&mut BytesMut),
{
    // Build inner first so we know its length, then write the outer length
    // prefix.
    let mut inner = BytesMut::new();
    inner.write_varint(transaction_id as i32);
    inner.write_varint(message_id as i32);
    write_body(&mut inner);

    let mut out = BytesMut::with_capacity(varint::varint_len(inner.len() as i32) + inner.len());
    out.write_varint(inner.len() as i32);
    out.put_slice(&inner);
    out.freeze()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_empty_body() {
        let frame = encode_frame(0, 6 /* Ping */, |_| {});
        // Decode again.
        let mut buf = BytesMut::from(&frame[..]);
        let parsed = try_decode_frame(&mut buf).unwrap().unwrap();
        assert_eq!(parsed.transaction_id, 0);
        assert_eq!(parsed.message_id, 6);
        assert!(parsed.body.is_empty());
        assert!(buf.is_empty());
    }

    #[test]
    fn returns_none_when_buffer_short() {
        let frame = encode_frame(42, 5 /* Hello */, |body| {
            body.write_string("server-1");
        });
        // Only feed the first byte.
        let mut buf = BytesMut::from(&frame[..1]);
        assert!(try_decode_frame(&mut buf).unwrap().is_none());
    }

    #[test]
    fn handles_two_frames_back_to_back() {
        let a = encode_frame(1, 6, |_| {});
        let b = encode_frame(2, 6, |_| {});
        let mut buf = BytesMut::new();
        buf.extend_from_slice(&a);
        buf.extend_from_slice(&b);

        let first = try_decode_frame(&mut buf).unwrap().unwrap();
        assert_eq!(first.transaction_id, 1);
        let second = try_decode_frame(&mut buf).unwrap().unwrap();
        assert_eq!(second.transaction_id, 2);
        assert!(buf.is_empty());
    }

    #[test]
    fn rejects_oversize_frame_in_header() {
        let mut buf = BytesMut::new();
        // varint encoding of (MAX_FRAME_SIZE + 1) -> larger than allowed
        buf.write_varint((MAX_FRAME_SIZE as i32).wrapping_add(1));
        assert!(matches!(
            try_decode_frame(&mut buf),
            Err(ProtocolError::FrameTooLarge { .. })
        ));
    }
}
