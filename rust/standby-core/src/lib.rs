uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn core_version() -> String {
    format!("standby-core v{}", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_non_empty() {
        let ver = core_version();
        assert!(ver.starts_with("standby-core v"));
    }
}
