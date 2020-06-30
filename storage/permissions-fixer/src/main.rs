use clap::{App, Arg};

use crate::config::show_config;
use crate::errors::Failure;
use crate::errors::Failure::PathCannotBeEmpty;
use crate::path::apply_permissions;

mod config;
mod errors;
mod path;

fn main() -> Result<(), Failure> {
    let args = App::new("nexus-fixer")
        .version("1.0.0")
        .author("BlueBrain Nexus <http://github.com/BlueBrain/nexus>")
        .about("Utility to fix permissions on files moved by the Nexus storage service.")
        .arg(
            Arg::with_name("PATH")
                .help("The target path")
                .required(true)
                .conflicts_with("show-config")
                .index(1),
        )
        .arg(
            Arg::with_name("show-config")
                .short("s")
                .long("show-config")
                .help("Prints compile-time configuration")
                .conflicts_with("PATH"),
        )
        .get_matches();

    if args.is_present("show-config") {
        return show_config();
    }

    match args.value_of("PATH") {
        Some(path) => apply_permissions(path).map(|_| println!("Done.")),
        None => Err(PathCannotBeEmpty),
    }
}
