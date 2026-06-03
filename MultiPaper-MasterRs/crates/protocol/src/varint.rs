//! Minecraft-style VarInt: 7 bits per byte, MSB is the continuation flag.
//!
//! Matches the Java reference in `ExtendedByteBuf.readVarInt` /
//! `ExtendedByteBuf.writeVarInt`. A 32-bit value uses at most 5 bytes.

use crate::error::{ProtocolError, ProtocolResult};
use bytes::{Buf, BufMut};

pub const MAX_BYTES: usize = 5;

pub fn read_varint<B: Buf + ?Sized>(buf: &mut B) -> ProtocolResult<i32> {
    let mut value: u32 = 0;
    for shift_index in 0..MAX_BYTES {
        if !buf.has_remaining() {
            return Err(ProtocolError::UnexpectedEof { needed: 1 });
        }
        let byte = buf.get_u8();
        value |= ((byte & 0x7F) as u32) << (shift_index * 7);
        if byte & 0x80 == 0 {
            return Ok(value as i32);
        }
    }
    Err(ProtocolError::VarIntTooBig)
}

pub fn write_varint<B: BufMut + ?Sized>(buf: &mut B, value: i32) {
    // Mirror Java's `value >>>= 7` (logical right shift on a 32-bit register).
    let mut v = value as u32;
    while v & !0x7F != 0 {
        buf.put_u8((v as u8 & 0x7F) | 0x80);
        v >>= 7;
    }
    buf.put_u8(v as u8);
}

/// Byte length of the VarInt encoding of `value`.
pub fn varint_len(value: i32) -> usize {
    let mut v = value as u32;
    let mut len = 1;
    while v & !0x7F != 0 {
        len += 1;
        v >>= 7;
    }
    len
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BytesMut;

    fn roundtrip(value: i32, expected_bytes: &[u8]) {
        let mut buf = BytesMut::new();
        write_varint(&mut buf, value);
        assert_eq!(&buf[..], expected_bytes, "encoding of {value}");
        assert_eq!(varint_len(value), expected_bytes.len(), "length of {value}");

        let mut slice = &expected_bytes[..];
        let decoded = read_varint(&mut slice).expect("decode");
        assert_eq!(decoded, value, "decoding of {value}");
    }

    #[test]
    fn encoding_matches_minecraft_spec() {
        // Well-known test vectors from the Minecraft wiki.
        roundtrip(0, &[0x00]);
        roundtrip(1, &[0x01]);
        roundtrip(2, &[0x02]);
        roundtrip(127, &[0x7F]);
        roundtrip(128, &[0x80, 0x01]);
        roundtrip(255, &[0xFF, 0x01]);
        roundtrip(25565, &[0xDD, 0xC7, 0x01]);
        roundtrip(2097151, &[0xFF, 0xFF, 0x7F]);
        roundtrip(2147483647, &[0xFF, 0xFF, 0xFF, 0xFF, 0x07]);
        roundtrip(-1, &[0xFF, 0xFF, 0xFF, 0xFF, 0x0F]);
        roundtrip(-2147483648, &[0x80, 0x80, 0x80, 0x80, 0x08]);
    }

    #[test]
    fn rejects_overlong_varint() {
        let bad = [0xFFu8; 6];
        let mut slice = &bad[..];
        assert!(matches!(
            read_varint(&mut slice),
            Err(ProtocolError::VarIntTooBig)
        ));
    }

    #[test]
    fn rejects_truncated_varint() {
        let bad = [0x80u8];
        let mut slice = &bad[..];
        assert!(matches!(
            read_varint(&mut slice),
            Err(ProtocolError::UnexpectedEof { .. })
        ));
    }
}
