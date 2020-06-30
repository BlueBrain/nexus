## Nexus storage permissions fixer

### Setup

Use [rustup](https://rustup.rs).

### Build with custom configuration

```bash
NEXUS_PATH_PREFIX=/path/to/gpfs/ NEXUS_USER_ID=12345 NEXUS_GROUP_ID=67890 cargo build --release
```

### Run tests in Docker

```bash
docker build . --tag=nexus/fixer
docker run -it nexus/fixer
```

### Usage

#### Apply permissions

```bash
nexus-fixer PATH
```

Where `PATH` is an absolute and valid path to a file or directory.
If `PATH` points to a directory, permissions are applied recursively on the directory and all its children.

#### Show compile-time configuration

```bash
nexus-fixer -s
```

#### Show help

```bash
nexus-fixer -h
```
