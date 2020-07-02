use libc::{gid_t, uid_t};

use crate::errors::Failure;
use crate::errors::Failure::{InvalidGroupId, InvalidUserId};

#[allow(dead_code)]
mod built_info {
    include!(concat!(env!("OUT_DIR"), "/built.rs"));
}

const PATH_PREFIX: Option<&'static str> = option_env!("NEXUS_PATH_PREFIX");
const PATH_PREFIX_DEFAULT: &'static str = "/tmp/";

const UID: Option<&'static str> = option_env!("NEXUS_USER_ID");
const UID_DEFAULT: uid_t = 1000;

const GID: Option<&'static str> = option_env!("NEXUS_GROUP_ID");
const GID_DEFAULT: gid_t = 1000;

pub const FILE_MASK: u32 = 0o440;
pub const CHMOD_MASK_WX_GROUP: u32 = 0b000011000;
pub const DIR_MASK: u32 = 0o750;

pub fn get_path_prefix() -> &'static str {
    PATH_PREFIX.unwrap_or(PATH_PREFIX_DEFAULT)
}

pub fn get_uid() -> Result<uid_t, Failure> {
    match UID {
        Some(uid) => uid.parse::<uid_t>().map_err(|_| InvalidUserId),
        None => Ok(UID_DEFAULT),
    }
}

pub fn get_gid() -> Result<gid_t, Failure> {
    match GID {
        Some(gid) => gid.parse::<gid_t>().map_err(|_| InvalidGroupId),
        None => Ok(GID_DEFAULT),
    }
}

pub fn show_config() -> Result<(), Failure> {
    println!("Package version: {}", built_info::PKG_VERSION);
    println!(
        "Git commit: {}",
        built_info::GIT_VERSION.unwrap_or("Unknown")
    );
    println!("Rust version: {}", built_info::RUSTC_VERSION);
    println!("Platform: {}", built_info::TARGET);
    println!("Path prefix: {}", get_path_prefix());
    println!("UID: {}", get_uid()?);
    println!("GID: {}", get_gid()?);
    println!("File permissions mask: {:o}", FILE_MASK);
    println!("Directory permissions mask: {:o}", DIR_MASK);
    Ok(())
}
