//! Messages sent master -> server.
//!
//! Variant order matches `ServerBoundProtocol.java`. Append-only.

use crate::{
    codec::{ReadExt, WriteExt},
    error::{ProtocolError, ProtocolResult},
};
use bytes::{Buf, BufMut};

/// Issued by master right after a successful Hello. The server uses this as
/// the auth token when establishing peer-to-peer connections.
#[derive(Debug, Clone)]
pub struct SetSecret {
    pub secret: String,
}

/// Heartbeat-style broadcast: master tells every server what TPS each peer is
/// running at, so they can decide load-balancing things locally.
#[derive(Debug, Clone)]
pub struct ServerInfoUpdate {
    pub name: String,
    pub average_tick_time: i32,
    pub tps: f32,
}

#[derive(Debug, Clone)]
pub enum ServerBound {
    /// id=0
    DataStream(UnimplementedMessage),
    /// id=1
    ServerInfoUpdate(ServerInfoUpdate),
    /// id=2
    SetSecret(SetSecret),
    /// id=3
    Shutdown(UnimplementedMessage),
    /// id=4
    ServerChangedChunkStatus(UnimplementedMessage),
    /// id=5
    FileContent(UnimplementedMessage),
    /// id=6
    DataReply(UnimplementedMessage),
    /// id=7
    SetChunkOwner(UnimplementedMessage),
    /// id=8
    BooleanReply(UnimplementedMessage),
    /// id=9
    ChunkLoadedOnAnotherServer(UnimplementedMessage),
    /// id=10
    FilesToSync(UnimplementedMessage),
    /// id=11
    ServerStarted(UnimplementedMessage),
    /// id=12
    DataUpdate(UnimplementedMessage),
    /// id=13
    AddChunkSubscriber(UnimplementedMessage),
    /// id=14
    AddEntitySubscriber(UnimplementedMessage),
    /// id=15
    RemoveChunkSubscriber(UnimplementedMessage),
    /// id=16
    RemoveEntitySubscriber(UnimplementedMessage),
    /// id=17
    ChunkSubscribersSync(UnimplementedMessage),
    /// id=18
    EntitySubscribersSync(UnimplementedMessage),
    /// id=19
    DataStreamReply(UnimplementedMessage),
    /// id=20
    NullableStringReply(UnimplementedMessage),
    /// id=21
    KeyValueStringMapReply(UnimplementedMessage),
    /// id=22
    IntegerPairReply(UnimplementedMessage),
}

impl ServerBound {
    pub fn message_id(&self) -> u32 {
        match self {
            Self::DataStream(_) => 0,
            Self::ServerInfoUpdate(_) => 1,
            Self::SetSecret(_) => 2,
            Self::Shutdown(_) => 3,
            Self::ServerChangedChunkStatus(_) => 4,
            Self::FileContent(_) => 5,
            Self::DataReply(_) => 6,
            Self::SetChunkOwner(_) => 7,
            Self::BooleanReply(_) => 8,
            Self::ChunkLoadedOnAnotherServer(_) => 9,
            Self::FilesToSync(_) => 10,
            Self::ServerStarted(_) => 11,
            Self::DataUpdate(_) => 12,
            Self::AddChunkSubscriber(_) => 13,
            Self::AddEntitySubscriber(_) => 14,
            Self::RemoveChunkSubscriber(_) => 15,
            Self::RemoveEntitySubscriber(_) => 16,
            Self::ChunkSubscribersSync(_) => 17,
            Self::EntitySubscribersSync(_) => 18,
            Self::DataStreamReply(_) => 19,
            Self::NullableStringReply(_) => 20,
            Self::KeyValueStringMapReply(_) => 21,
            Self::IntegerPairReply(_) => 22,
        }
    }

    pub fn decode_body<B: Buf>(id: u32, transaction_id: u32, buf: &mut B) -> ProtocolResult<Self> {
        match id {
            1 => Ok(Self::ServerInfoUpdate(ServerInfoUpdate {
                name: buf.read_string()?,
                average_tick_time: buf.read_i32_be()?,
                tps: f32::from_bits(buf.read_i32_be()? as u32),
            })),
            2 => Ok(Self::SetSecret(SetSecret {
                secret: buf.read_string()?,
            })),
            other => Err(ProtocolError::UnknownMessageId {
                id: other,
                transaction: transaction_id,
            }),
        }
    }

    pub fn encode_body<B: BufMut>(&self, buf: &mut B) {
        match self {
            Self::ServerInfoUpdate(m) => {
                buf.write_string(&m.name);
                buf.put_i32(m.average_tick_time);
                buf.put_i32(m.tps.to_bits() as i32);
            }
            Self::SetSecret(m) => {
                buf.write_string(&m.secret);
            }
            _ => panic!(
                "encoding of message id {} not yet implemented",
                self.message_id()
            ),
        }
    }
}

#[derive(Debug, Clone)]
pub struct UnimplementedMessage(pub Vec<u8>);

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::BytesMut;

    #[test]
    fn set_secret_roundtrip() {
        let msg = ServerBound::SetSecret(SetSecret {
            secret: "topsecret".into(),
        });
        let mut buf = BytesMut::new();
        msg.encode_body(&mut buf);
        let mut slice = &buf[..];
        match ServerBound::decode_body(2, 0, &mut slice).unwrap() {
            ServerBound::SetSecret(s) => assert_eq!(s.secret, "topsecret"),
            other => panic!("wrong variant: {other:?}"),
        }
    }

    #[test]
    fn server_info_update_roundtrip() {
        let msg = ServerBound::ServerInfoUpdate(ServerInfoUpdate {
            name: "server-3".into(),
            average_tick_time: 47,
            tps: 19.94,
        });
        let mut buf = BytesMut::new();
        msg.encode_body(&mut buf);
        let mut slice = &buf[..];
        match ServerBound::decode_body(1, 0, &mut slice).unwrap() {
            ServerBound::ServerInfoUpdate(s) => {
                assert_eq!(s.name, "server-3");
                assert_eq!(s.average_tick_time, 47);
                assert!((s.tps - 19.94).abs() < 1e-5);
            }
            other => panic!("wrong variant: {other:?}"),
        }
    }
}
