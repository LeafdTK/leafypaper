use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub bind: String,
    pub secret: String,
}

impl Default for Config {
    fn default() -> Self {
        // Same default port as the Java master.
        Self {
            bind: "0.0.0.0:35353".into(),
            secret: "change-me".into(),
        }
    }
}

impl Config {
    pub fn load_or_default(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let path = path.as_ref();
        if path.exists() {
            let text = std::fs::read_to_string(path)?;
            Ok(serde_yaml::from_str(&text)?)
        } else {
            tracing::warn!(
                "{} not found, using defaults (bind 0.0.0.0:35353, secret 'change-me')",
                path.display()
            );
            Ok(Self::default())
        }
    }
}
