use thiserror::Error;

pub type ProtocolResult<T> = Result<T, ProtocolError>;

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("varint too big (more than 5 continuation bytes)")]
    VarIntTooBig,

    #[error("unexpected end of buffer (needed {needed} more bytes)")]
    UnexpectedEof { needed: usize },

    #[error("invalid utf-8 in string field: {0}")]
    InvalidUtf8(#[from] std::string::FromUtf8Error),

    #[error("unknown message id {id} (transaction {transaction})")]
    UnknownMessageId { id: u32, transaction: u32 },

    #[error("frame too large: {size} bytes (max {max})")]
    FrameTooLarge { size: usize, max: usize },
}
