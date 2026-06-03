use crate::{config::Config, connection, state::State};
use std::sync::Arc;
use tokio::net::TcpListener;

pub async fn serve(config: &Config, state: Arc<State>) -> anyhow::Result<()> {
    let listener = TcpListener::bind(&config.bind).await?;
    tracing::info!(bind = %config.bind, "listening for MultiPaper servers");

    loop {
        let (socket, addr) = listener.accept().await?;
        socket.set_nodelay(true)?;
        let state = state.clone();
        let secret = config.secret.clone();
        tokio::spawn(async move {
            if let Err(err) = connection::handle(socket, addr, state, secret).await {
                tracing::warn!(%addr, %err, "connection closed with error");
            }
        });
    }
}
