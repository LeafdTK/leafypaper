//! Per-connection state machine: read frames, dispatch on message id,
//! write replies.
//!
//! The Java master sends a `SetSecretMessage` immediately after the server's
//! `HelloMessage` (see `ServerConnection.send(new SetSecretMessage(...))` in
//! `MultiPaper-Master`). We mirror that exactly so existing servers see no
//! behavioral change.

use crate::state::{ServerEntry, State};
use bytes::BytesMut;
use multipaper_protocol::{
    frame::{encode_frame, try_decode_frame},
    messages::{
        masterbound::MasterBound,
        serverbound::{ServerBound, SetSecret},
    },
};
use std::{net::SocketAddr, sync::Arc};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

const READ_CHUNK: usize = 16 * 1024;

pub async fn handle(
    socket: TcpStream,
    addr: SocketAddr,
    state: Arc<State>,
    secret: String,
) -> anyhow::Result<()> {
    let (mut read, mut write) = socket.into_split();
    let mut buf = BytesMut::with_capacity(READ_CHUNK);
    let mut server_name: Option<String> = None;

    loop {
        // Try to parse a complete frame from whatever we have buffered.
        match try_decode_frame(&mut buf)? {
            Some(frame) => {
                let msg = match MasterBound::decode_body(
                    frame.message_id,
                    frame.transaction_id,
                    &mut frame.body.clone(),
                ) {
                    Ok(m) => m,
                    Err(e) => {
                        // Unknown / not-yet-ported message: log and keep going.
                        // The Java side won't be sending IDs we don't know
                        // about, so this primarily helps during development.
                        tracing::warn!(
                            %addr,
                            id = frame.message_id,
                            transaction = frame.transaction_id,
                            error = %e,
                            "skipping unhandled message"
                        );
                        continue;
                    }
                };

                match msg {
                    MasterBound::Hello(hello) => {
                        tracing::info!(
                            %addr,
                            server_name = %hello.name,
                            uuid = %hello.server_uuid,
                            "server handshake"
                        );
                        state.servers.insert(
                            hello.name.clone(),
                            ServerEntry {
                                uuid: hello.server_uuid,
                                remote_addr: addr,
                            },
                        );
                        server_name = Some(hello.name);

                        // Reply with SetSecret on transaction 0 (broadcast).
                        let reply = ServerBound::SetSecret(SetSecret {
                            secret: secret.clone(),
                        });
                        send_message(&mut write, 0, &reply).await?;
                    }
                    MasterBound::Ping(_) => {
                        // The Java client uses transaction-id callbacks for
                        // ping; the master echoes the message back with the
                        // same transaction id and the server treats *any*
                        // reply on that id as a pong.
                        tracing::trace!(%addr, "ping");
                        let pong = MasterBound::Ping(Default::default());
                        let payload = encode_frame(frame.transaction_id, pong.message_id(), |b| {
                            pong.encode_body(b)
                        });
                        write.write_all(&payload).await?;
                    }
                    other => {
                        tracing::debug!(?other, "no handler yet for this masterbound variant");
                    }
                }
            }
            None => {
                // Need more bytes.
                if buf.capacity() - buf.len() < READ_CHUNK {
                    buf.reserve(READ_CHUNK);
                }
                let n = read.read_buf(&mut buf).await?;
                if n == 0 {
                    tracing::info!(%addr, server = ?server_name, "peer closed");
                    if let Some(name) = &server_name {
                        state.servers.remove(name);
                    }
                    return Ok(());
                }
            }
        }
    }
}

async fn send_message<W: AsyncWriteExt + Unpin>(
    write: &mut W,
    transaction_id: u32,
    msg: &ServerBound,
) -> anyhow::Result<()> {
    let bytes = encode_frame(transaction_id, msg.message_id(), |b| msg.encode_body(b));
    write.write_all(&bytes).await?;
    Ok(())
}
