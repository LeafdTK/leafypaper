//! All protocol messages.
//!
//! Message IDs MUST stay in lock-step with the Java `MasterBoundProtocol` and
//! `ServerBoundProtocol` classes — they are assigned by the registration order
//! in the Java constructor. Any reordering breaks the wire.

pub mod masterbound;
pub mod serverbound;

pub use masterbound::MasterBound;
pub use serverbound::ServerBound;
