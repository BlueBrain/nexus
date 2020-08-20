use std::convert::TryInto;
use std::ffi::CString;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::Path;

use libc::{chmod, chown};
use walkdir::WalkDir;

use crate::config::*;
use crate::errors::Failure;
use crate::errors::Failure::*;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::fs::Metadata;

pub fn apply_permissions(path: &str) -> Result<(), Failure> {
    check_path(path).and_then(check_links).and_then(visit_all)
}

fn check_path(path: &str) -> Result<&Path, Failure> {
    let p = Path::new(path);
    if p.is_relative() {
        Err(PathMustBeAbsolute)
    } else if !p.starts_with(get_path_prefix()) {
        Err(PathMustStartWithPrefix)
    } else if !p.exists() {
        Err(FileNotFound)
    } else if p.canonicalize()? != p.to_path_buf() {
        Err(PathMustBeCanonical)
    } else {
        Ok(p)
    }
}

fn check_links(path: &Path) -> Result<&Path, Failure> {
    let result: Result<Vec<bool>, Failure> = WalkDir::new(path)
        .into_iter()
        .map(|e| {
            let entry = e?;
            let meta = entry.metadata()?;
            Ok(entry.path_is_symlink() || (meta.is_file() && meta.nlink() > 1))
        })
        .collect();
    result.and_then(|links| {
        if links.into_iter().any(|b| b) {
            Err(PathCannotContainLinks)
        } else {
            Ok(path)
        }
    })
}

fn add_parent_permissions(parent: &Path) -> Result<(), Failure> {
    let mask = fetch_permissions(parent) | CHMOD_MASK_WX_GROUP;
    set_permissions(parent, mask)
}

fn visit_all(path: &Path) -> Result<(), Failure> {
    let parent_result = path.parent().map_or(Ok(()), |p| add_parent_permissions(p).and_then(|_| set_group(p)));
    let result: Result<Vec<()>, Failure> = WalkDir::new(path)
        .into_iter()
        .map(|e| {
            let entry = e?;
            let p = entry.path();
            if p.is_dir() {
                set_owner(p).and_then(|_| set_permissions(p, DIR_MASK))
            } else {
                set_owner(p).and_then(|_| set_permissions(p, FILE_MASK))
            }
        })
        .collect();
    parent_result.and_then(|_| result.map(|_| ()))
}

fn set_owner(path: &Path) -> Result<(), Failure> {
    set_custom_owner(path, get_uid()?, get_gid()?)
}

fn set_group(path: &Path) -> Result<(), Failure> {
    let metadata = fetch_metadata(path);
    set_custom_owner(path, metadata.uid(), get_gid()?)
}

fn set_custom_owner(path: &Path, uid: u32, gid: u32) -> Result<(), Failure> {
    let p = CString::new(path.as_os_str().as_bytes()).map_err(|_| PathCannotHaveNull)?;
    let chown = unsafe { chown(p.as_ptr() as *const i8, uid, gid) };
    if chown == 0 {
        Ok(())
    } else {
        Err(ChownFailed)
    }
}

fn set_permissions(path: &Path, mask: u32) -> Result<(), Failure> {
    let p = CString::new(path.as_os_str().as_bytes()).map_err(|_| PathCannotHaveNull)?;
    let chmod = unsafe { chmod(p.as_ptr() as *const i8, mask.try_into().unwrap()) };
    if chmod == 0 {
        Ok(())
    } else {
        Err(ChmodFailed)
    }
}

fn fetch_permissions(path: &Path) -> u32 {
    fetch_metadata(path).permissions().mode()
}

fn fetch_metadata(path: &Path) -> Metadata {
    fs::metadata(path).expect("failed to read file metadata")
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::os::unix::fs::{symlink, MetadataExt};

    use rand::{thread_rng, Rng};

    use super::*;

    #[test]
    fn test_check_path() {
        setup();
        assert_eq!(check_path("../foo"), Err(PathMustBeAbsolute));
        assert_eq!(check_path("/foo"), Err(PathMustStartWithPrefix));
        assert_eq!(check_path("/tmp/nexus-fixer/bar"), Err(FileNotFound));
        assert!(touch(Path::new("/tmp/nexus-fixer/bar")).is_ok());
        assert!(check_path("/tmp/nexus-fixer/bar").is_ok());
        assert_eq!(
            check_path("/tmp/nexus-fixer/../nexus-fixer/bar"),
            Err(PathMustBeCanonical)
        );
    }

    #[test]
    fn test_set_owner() {
        setup();
        let p = Path::new("/tmp/nexus-fixer/baz");
        assert!(touch(p).is_ok());
        assert!(set_owner(p).is_ok());
        check_owner(
            p,
            get_uid().expect("failed to read UID"),
            get_gid().expect("failed to read GID"),
        );
        assert!(fs::remove_file(p).is_ok());
    }

    #[test]
    fn test_set_group() {
        setup();
        let p = Path::new("/tmp/nexus-fixer/batman");
        assert!(touch(p).is_ok());
        assert!(set_owner(p).is_ok());
        check_group(
            p,
            get_gid().expect("failed to read GID"),
        );
        assert!(fs::remove_file(p).is_ok());
    }

    #[test]
    fn test_set_permissions() {
        setup();
        let p = Path::new("/tmp/nexus-fixer/qux");
        let mask = random_mask();
        assert!(touch(p).is_ok());
        assert!(set_permissions(p, mask).is_ok());
        check_permissions(p, mask);
        assert!(fs::remove_file(p).is_ok());
    }

    #[test]
    fn test_check_links() {
        setup();
        let p = Path::new("/tmp/nexus-fixer/d/e/f");
        assert!(fs::create_dir_all(p).is_ok());
        let file_a = Path::new("/tmp/nexus-fixer/d/file_a");
        let file_b = Path::new("/tmp/nexus-fixer/d/e/file_b");
        let file_c = Path::new("/tmp/nexus-fixer/d/e/f/file_c");
        assert!(touch(file_a).is_ok());
        assert!(touch(file_b).is_ok());
        assert!(touch(file_c).is_ok());

        let toplevel = Path::new("/tmp/nexus-fixer/d");
        assert!(check_links(toplevel).is_ok());

        let softlink = Path::new("/tmp/nexus-fixer/d/symlink");
        assert!(symlink(file_a, softlink).is_ok());
        assert_eq!(check_links(toplevel), Err(PathCannotContainLinks));
        assert!(fs::remove_file(softlink).is_ok());

        let hardlink = Path::new("/tmp/nexus-fixer/d/hardlink");
        assert!(fs::hard_link(file_b, hardlink).is_ok());
        assert_eq!(check_links(toplevel), Err(PathCannotContainLinks));
        assert!(fs::remove_dir_all(toplevel).is_ok());
    }

    #[test]
    fn test_visit_all() {
        setup();
        let p = Path::new("/tmp/nexus-fixer/a/b/c");
        assert!(fs::create_dir_all(p).is_ok());
        let file_a = Path::new("/tmp/nexus-fixer/a/file_a");
        let file_b = Path::new("/tmp/nexus-fixer/a/b/file_b");
        let file_c = Path::new("/tmp/nexus-fixer/a/b/c/file_c");
        assert!(touch(file_a).is_ok());
        assert!(touch(file_b).is_ok());
        assert!(touch(file_c).is_ok());
        check_permissions(Path::new("/tmp/nexus-fixer/"), 0o755);
        assert!(visit_all(Path::new("/tmp/nexus-fixer/a")).is_ok());

        let uid = get_uid().expect("failed to read UID");
        let gid = get_gid().expect("failed to read GID");

        // dirs
        check_permissions(Path::new("/tmp/nexus-fixer/a"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a"), uid, gid);
        check_permissions(Path::new("/tmp/nexus-fixer/a/b"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a/b"), uid, gid);
        check_permissions(Path::new("/tmp/nexus-fixer/a/b/c"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a/b/c"), uid, gid);
        check_permissions(Path::new("/tmp/nexus-fixer/"), 0o775);

        // files
        check_permissions(file_a, FILE_MASK);
        check_owner(file_a, uid, gid);
        check_permissions(file_b, FILE_MASK);
        check_owner(file_b, uid, gid);
        check_permissions(file_c, FILE_MASK);
        check_owner(file_c, uid, gid);
        assert!(fs::remove_dir_all(Path::new("/tmp/nexus-fixer/a")).is_ok());
    }

    fn check_owner(path: &Path, uid: u32, gid: u32) {
        let metadata = fetch_metadata(path);
        assert_eq!(metadata.uid(), uid);
        assert_eq!(metadata.gid(), gid);
    }

    fn check_group(path: &Path, gid: u32) {
        let metadata = fetch_metadata(path);
        assert_eq!(metadata.gid(), gid);
    }

    fn check_permissions(path: &Path, mask: u32) {
        assert_eq!(fetch_permissions(path) & 0o777, mask);
    }

    fn random_mask() -> u32 {
        thread_rng().gen_range(0, 0o1000)
    }

    // A simple implementation of `% touch path` (ignores existing files)
    fn touch(path: &Path) -> io::Result<()> {
        match fs::OpenOptions::new().create(true).write(true).open(path) {
            Ok(_) => Ok(()),
            Err(e) => Err(e),
        }
    }

    fn setup() {
        assert!(fs::create_dir_all("/tmp/nexus-fixer").is_ok());
    }
}
