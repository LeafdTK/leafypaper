//! MultiPaper master server — Rust reimplementation.
//!
//! Wire-compatible drop-in replacement for the Java master. Speaks the same
//! length-prefixed binary protocol on the same TCP port, so existing
//! MultiPaper-Server instances can connect without code changes.

mod config;
mod connection;
mod listener;
mod state;

use anyhow::Context;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .init();

    let config = config::Config::load_or_default("multipaper-master.yml")
        .context("loading multipaper-master.yml")?;
    tracing::info!(?config, "starting MultiPaper master (Rust)");

    let state = std::sync::Arc::new(state::State::new());
    listener::serve(&config, state).await
}
