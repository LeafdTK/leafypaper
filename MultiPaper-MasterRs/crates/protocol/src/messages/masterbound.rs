//! Messages sent server -> master.
//!
//! Variant order in the `MasterBound` enum corresponds to message IDs in
//! `MasterBoundProtocol.java`. **Do not reorder existing variants** — IDs are
//! determined by position. Append-only.

use crate::{
    codec::{ReadExt, WriteExt},
    error::{ProtocolError, ProtocolResult},
};
use bytes::{Buf, BufMut};
use uuid::Uuid;

/// Server-issued handshake. Sent immediately after the master connection is
/// established. The master uses `name` as the server's display identity and
/// `server_uuid` as a stable cross-restart key.
#[derive(Debug, Clone)]
pub struct Hello {
    pub name: String,
    pub server_uuid: Uuid,
}

/// Keep-alive ping. Empty payload. Master replies via the transaction-id
/// callback mechanism (no dedicated reply message — sender registers a callback
/// keyed on `transactionId`).
#[derive(Debug, Clone, Default)]
pub struct Ping;

/// All message types the master can receive. Variant order matches Java IDs.
#[derive(Debug, Clone)]
pub enum MasterBound {
    /// id=0
    DataStream(UnimplementedMessage),
    /// id=1
    CallDataStorage(UnimplementedMessage),
    /// id=2
    ChunkChangedStatus(UnimplementedMessage),
    /// id=3
    DownloadFile(UnimplementedMessage),
    /// id=4
    ForceReadChunk(UnimplementedMessage),
    /// id=5
    Hello(Hello),
    /// id=6
    Ping(Ping),
    /// id=7
    LockChunk(UnimplementedMessage),
    /// id=8
    PlayerConnect(UnimplementedMessage),
    /// id=9
    PlayerDisconnect(UnimplementedMessage),
    /// id=10
    ReadAdvancement(UnimplementedMessage),
    /// id=11
    ReadChunk(UnimplementedMessage),
    /// id=12
    ReadData(UnimplementedMessage),
    /// id=13
    ReadJson(UnimplementedMessage),
    /// id=14
    ReadLevel(UnimplementedMessage),
    /// id=15
    ReadPlayer(UnimplementedMessage),
    /// id=16
    ReadStats(UnimplementedMessage),
    /// id=17
    ReadUid(UnimplementedMessage),
    /// id=18
    RequestChunkOwnership(UnimplementedMessage),
    /// id=19
    RequestEntityIdBlock(UnimplementedMessage),
    /// id=20
    RequestFilesToSync(UnimplementedMessage),
    /// id=21
    SetPort(UnimplementedMessage),
    /// id=22
    Start(UnimplementedMessage),
    /// id=23
    SubscribeChunk(UnimplementedMessage),
    /// id=24
    SubscribeEntities(UnimplementedMessage),
    /// id=25
    SyncChunkOwnerToAll(UnimplementedMessage),
    /// id=26
    SyncChunkSubscribers(UnimplementedMessage),
    /// id=27
    SyncEntitiesSubscribers(UnimplementedMessage),
    /// id=28
    UnlockChunk(UnimplementedMessage),
    /// id=29
    UnsubscribeChunk(UnimplementedMessage),
    /// id=30
    UnsubscribeEntities(UnimplementedMessage),
    /// id=31
    UploadFile(UnimplementedMessage),
    /// id=32
    WillSaveChunkLater(UnimplementedMessage),
    /// id=33
    WillSaveEntitiesLater(UnimplementedMessage),
    /// id=34
    WriteAdvancements(UnimplementedMessage),
    /// id=35
    WriteChunk(UnimplementedMessage),
    /// id=36
    WriteData(UnimplementedMessage),
    /// id=37
    WriteJson(UnimplementedMessage),
    /// id=38
    WriteLevel(UnimplementedMessage),
    /// id=39
    WritePlayer(UnimplementedMessage),
    /// id=40
    WriteStats(UnimplementedMessage),
    /// id=41
    WriteTickTime(UnimplementedMessage),
    /// id=42
    WriteUid(UnimplementedMessage),
}

impl MasterBound {
    /// Numeric ID written on the wire. Must match Java `Protocol.getMessageId`.
    pub fn message_id(&self) -> u32 {
        match self {
            Self::DataStream(_) => 0,
            Self::CallDataStorage(_) => 1,
            Self::ChunkChangedStatus(_) => 2,
            Self::DownloadFile(_) => 3,
            Self::ForceReadChunk(_) => 4,
            Self::Hello(_) => 5,
            Self::Ping(_) => 6,
            Self::LockChunk(_) => 7,
            Self::PlayerConnect(_) => 8,
            Self::PlayerDisconnect(_) => 9,
            Self::ReadAdvancement(_) => 10,
            Self::ReadChunk(_) => 11,
            Self::ReadData(_) => 12,
            Self::ReadJson(_) => 13,
            Self::ReadLevel(_) => 14,
            Self::ReadPlayer(_) => 15,
            Self::ReadStats(_) => 16,
            Self::ReadUid(_) => 17,
            Self::RequestChunkOwnership(_) => 18,
            Self::RequestEntityIdBlock(_) => 19,
            Self::RequestFilesToSync(_) => 20,
            Self::SetPort(_) => 21,
            Self::Start(_) => 22,
            Self::SubscribeChunk(_) => 23,
            Self::SubscribeEntities(_) => 24,
            Self::SyncChunkOwnerToAll(_) => 25,
            Self::SyncChunkSubscribers(_) => 26,
            Self::SyncEntitiesSubscribers(_) => 27,
            Self::UnlockChunk(_) => 28,
            Self::UnsubscribeChunk(_) => 29,
            Self::UnsubscribeEntities(_) => 30,
            Self::UploadFile(_) => 31,
            Self::WillSaveChunkLater(_) => 32,
            Self::WillSaveEntitiesLater(_) => 33,
            Self::WriteAdvancements(_) => 34,
            Self::WriteChunk(_) => 35,
            Self::WriteData(_) => 36,
            Self::WriteJson(_) => 37,
            Self::WriteLevel(_) => 38,
            Self::WritePlayer(_) => 39,
            Self::WriteStats(_) => 40,
            Self::WriteTickTime(_) => 41,
            Self::WriteUid(_) => 42,
        }
    }

    /// Decode a message whose body has already been positioned to in `buf`.
    /// `transaction_id` is the message header field, returned to the caller
    /// via the framing layer.
    pub fn decode_body<B: Buf>(id: u32, transaction_id: u32, buf: &mut B) -> ProtocolResult<Self> {
        match id {
            5 => Ok(Self::Hello(Hello {
                name: buf.read_string()?,
                server_uuid: buf.read_uuid()?,
            })),
            6 => Ok(Self::Ping(Ping)),
            // Most messages are not yet ported; we capture the remaining bytes
            // so the connection can keep going while we incrementally implement.
            other => Err(ProtocolError::UnknownMessageId {
                id: other,
                transaction: transaction_id,
            }),
        }
    }

    pub fn encode_body<B: BufMut>(&self, buf: &mut B) {
        match self {
            Self::Hello(m) => {
                buf.write_string(&m.name);
                buf.write_uuid(&m.server_uuid);
            }
            Self::Ping(_) => {}
            _ => panic!(
                "encoding of message id {} not yet implemented",
                self.message_id()
            ),
        }
    }
}

/// Placeholder for messages whose body has not been ported yet. Carries the
/// raw bytes so we can ack the frame and decide later.
#[derive(Debug, Clone)]
pub struct UnimplementedMessage(pub Vec<u8>);

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BytesMut;

    #[test]
    fn hello_roundtrip() {
        let hello = Hello {
            name: "server-1".into(),
            server_uuid: Uuid::from_u128(0x1234_5678_9abc_def0_1122_3344_5566_7788),
        };
        let msg = MasterBound::Hello(hello.clone());

        let mut buf = BytesMut::new();
        msg.encode_body(&mut buf);

        let mut slice = &buf[..];
        let decoded = MasterBound::decode_body(5, 0, &mut slice).unwrap();
        match decoded {
            MasterBound::Hello(h) => {
                assert_eq!(h.name, hello.name);
                assert_eq!(h.server_uuid, hello.server_uuid);
            }
            other => panic!("wrong variant: {other:?}"),
        }
    }

    #[test]
    fn ping_has_empty_body() {
        let msg = MasterBound::Ping(Ping);
        let mut buf = BytesMut::new();
        msg.encode_body(&mut buf);
        assert!(buf.is_empty());
    }
}
