//! Shared master state. Currently a stub — chunk ownership, subscriptions,
//! and the file-storage backend will land here as messages are ported.

use dashmap::DashMap;
use uuid::Uuid;

#[derive(Debug)]
pub struct State {
    /// Connected servers keyed by their advertised name.
    pub servers: DashMap<String, ServerEntry>,
}

#[derive(Debug, Clone)]
#[allow(dead_code)] // surface as the ownership/subscription state lands
pub struct ServerEntry {
    pub uuid: Uuid,
    pub remote_addr: std::net::SocketAddr,
}

impl State {
    pub fn new() -> Self {
        Self {
            servers: DashMap::new(),
        }
    }
}
