use built::write_built_file;

fn main() {
    write_built_file().expect("Failed to acquire build-time information");
}
