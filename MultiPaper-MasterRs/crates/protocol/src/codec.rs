//! Buffer-level read/write helpers mirroring `ExtendedByteBuf` in Java.
//!
//! All multi-byte integers are big-endian, matching Netty's default for
//! `ByteBuf` (no `*LE` suffix used in the Java protocol).

use crate::{
    error::{ProtocolError, ProtocolResult},
    varint, ChunkKey,
};
use bytes::{Buf, BufMut};
use uuid::Uuid;

pub trait ReadExt: Buf {
    fn read_varint(&mut self) -> ProtocolResult<i32> {
        varint::read_varint(self)
    }

    fn read_string(&mut self) -> ProtocolResult<String> {
        let len = self.read_varint()? as usize;
        ensure_remaining(self, len)?;
        let mut bytes = vec![0u8; len];
        self.copy_to_slice(&mut bytes);
        Ok(String::from_utf8(bytes)?)
    }

    fn read_uuid(&mut self) -> ProtocolResult<Uuid> {
        ensure_remaining(self, 16)?;
        let msb = self.get_i64();
        let lsb = self.get_i64();
        Ok(Uuid::from_u64_pair(msb as u64, lsb as u64))
    }

    fn read_chunk_key(&mut self) -> ProtocolResult<ChunkKey> {
        let world = self.read_string()?;
        ensure_remaining(self, 8)?;
        let x = self.get_i32();
        let z = self.get_i32();
        Ok(ChunkKey { world, x, z })
    }

    fn read_bool(&mut self) -> ProtocolResult<bool> {
        ensure_remaining(self, 1)?;
        Ok(self.get_u8() != 0)
    }

    fn read_i32_be(&mut self) -> ProtocolResult<i32> {
        ensure_remaining(self, 4)?;
        Ok(self.get_i32())
    }

    fn read_i64_be(&mut self) -> ProtocolResult<i64> {
        ensure_remaining(self, 8)?;
        Ok(self.get_i64())
    }

    fn read_byte_array(&mut self) -> ProtocolResult<Vec<u8>> {
        let len = self.read_varint()? as usize;
        ensure_remaining(self, len)?;
        let mut bytes = vec![0u8; len];
        self.copy_to_slice(&mut bytes);
        Ok(bytes)
    }
}

impl<T: Buf + ?Sized> ReadExt for T {}

pub trait WriteExt: BufMut {
    fn write_varint(&mut self, value: i32) {
        varint::write_varint(self, value);
    }

    fn write_string(&mut self, value: &str) {
        let bytes = value.as_bytes();
        self.write_varint(bytes.len() as i32);
        self.put_slice(bytes);
    }

    fn write_uuid(&mut self, value: &Uuid) {
        let (msb, lsb) = value.as_u64_pair();
        self.put_i64(msb as i64);
        self.put_i64(lsb as i64);
    }

    fn write_chunk_key(&mut self, key: &ChunkKey) {
        self.write_string(&key.world);
        self.put_i32(key.x);
        self.put_i32(key.z);
    }

    fn write_bool(&mut self, value: bool) {
        self.put_u8(if value { 1 } else { 0 });
    }

    fn write_byte_array(&mut self, bytes: &[u8]) {
        self.write_varint(bytes.len() as i32);
        self.put_slice(bytes);
    }
}

impl<T: BufMut + ?Sized> WriteExt for T {}

fn ensure_remaining<B: Buf + ?Sized>(buf: &B, needed: usize) -> ProtocolResult<()> {
    if buf.remaining() < needed {
        Err(ProtocolError::UnexpectedEof {
            needed: needed - buf.remaining(),
        })
    } else {
        Ok(())
    }
}

/// A message that can be encoded onto a buffer.
pub trait Encode {
    fn encode<B: BufMut>(&self, buf: &mut B);
}

/// A message that can be decoded from a buffer.
pub trait Decode: Sized {
    fn decode<B: Buf>(buf: &mut B) -> ProtocolResult<Self>;
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BytesMut;

    #[test]
    fn string_roundtrip() {
        let mut buf = BytesMut::new();
        buf.write_string("hello");
        // varint(5) || 'h','e','l','l','o'
        assert_eq!(&buf[..], b"\x05hello");

        let mut slice = &buf[..];
        assert_eq!(slice.read_string().unwrap(), "hello");
    }

    #[test]
    fn uuid_roundtrip() {
        let uuid = Uuid::from_u64_pair(0x0123456789abcdef, 0xfedcba9876543210);
        let mut buf = BytesMut::new();
        buf.write_uuid(&uuid);
        let mut slice = &buf[..];
        assert_eq!(slice.read_uuid().unwrap(), uuid);
    }

    #[test]
    fn chunk_key_roundtrip() {
        let key = ChunkKey {
            world: "world".into(),
            x: -42,
            z: 1337,
        };
        let mut buf = BytesMut::new();
        buf.write_chunk_key(&key);
        let mut slice = &buf[..];
        assert_eq!(slice.read_chunk_key().unwrap(), key);
    }
}
