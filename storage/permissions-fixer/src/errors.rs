use std::error::Error;
use std::fmt;
use std::fmt::{Debug, Formatter};
use std::io;

use walkdir;

use Failure::*;

#[derive(Eq, PartialEq)]
pub enum Failure {
    PathCannotBeEmpty,
    PathCannotContainLinks,
    PathCannotHaveNull,
    PathMustBeAbsolute,
    PathMustBeCanonical,
    PathMustStartWithPrefix,
    FileNotFound,
    IOError(String),
    InvalidUserId,
    InvalidGroupId,
    ChmodFailed,
    ChownFailed,
}

impl From<io::Error> for Failure {
    fn from(ioe: io::Error) -> Self {
        Failure::IOError(ioe.description().to_owned())
    }
}

impl From<walkdir::Error> for Failure {
    fn from(we: walkdir::Error) -> Self {
        Failure::IOError(we.description().to_owned())
    }
}

impl Debug for Failure {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            IOError(desc) => write!(f, "IO exception: {}", desc),
            PathCannotBeEmpty => write!(f, "{}", "path cannot be empty"),
            PathCannotContainLinks => write!(f, "{}", "path cannot contain links"),
            PathCannotHaveNull => write!(f, "{}", "path cannot contain 'null' characters"),
            PathMustBeAbsolute => write!(f, "{}", "path must be absolute"),
            PathMustBeCanonical => write!(f, "{}", "path must be canonical"),
            PathMustStartWithPrefix => write!(f, "{}", "path must start with configured prefix"),
            FileNotFound => write!(f, "{}", "file not found"),
            InvalidUserId => write!(f, "{}", "invalid user ID"),
            InvalidGroupId => write!(f, "{}", "invalid group ID"),
            ChmodFailed => write!(f, "{}", "'chmod' operation failed"),
            ChownFailed => write!(f, "{}", "'chown' operation failed"),
        }
    }
}
