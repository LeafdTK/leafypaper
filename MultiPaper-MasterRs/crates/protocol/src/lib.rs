//! Wire protocol for MultiPaper master <-> server communication.
//!
//! Byte-compatible with the Java `MultiPaper-MasterMessagingProtocol`:
//! every frame is `varint(length) || varint(transactionId) || varint(messageId) || body`.
//! Strings are `varint(len) || utf8`, UUIDs are two big-endian i64s, all other
//! integers are big-endian (Netty's default ByteBuf endianness).

pub mod codec;
pub mod error;
pub mod frame;
pub mod messages;
pub mod varint;

pub use codec::{Decode, Encode, ReadExt, WriteExt};
pub use error::{ProtocolError, ProtocolResult};

/// A chunk identifier — world name + integer chunk coords.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ChunkKey {
    pub world: String,
    pub x: i32,
    pub z: i32,
}
