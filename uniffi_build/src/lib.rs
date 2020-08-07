/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use anyhow::Result;
use std::env;

#[cfg(not(feature = "builtin-bindgen"))]
use anyhow::{bail, Context};
#[cfg(not(feature = "builtin-bindgen"))]
use std::process::Command;

/// Generate the rust "scaffolding" required to build a uniffi component.
///
/// Given the path to an IDL file, this function will call the `uniffi-bindgen`
/// command-line tool to generate the `pub extern "C"` functions and other supporting
/// code required to expose the defined interface from Rust. The expectation is that
/// this will be called from a crate's build script, and the resulting file will
/// be `include!()`ed into the build.
///
/// Given an IDL file named `example.idl`, the generated scaffolding will be written
/// into a file named `example.uniffi.rs` in the `$OUT_DIR` directory.
///
/// If the "builtin-bindgen" feature is enabled then this will take a dependency on
/// the `uniffi_bindgen` crate and call its methods directly, rather than using the
/// command-line tool. This is mostly useful for developers who are working on uniffi
/// itself and need to test out their changes to the bindings generator.
pub fn generate_scaffolding(idl_file: &str) -> Result<()> {
    println!("cargo:rerun-if-changed={}", idl_file);
    // Why don't we just depend on uniffi-bindgen and call the public functions?
    // Calling the command line helps making sure that the generated swift/Kotlin/whatever
    // bindings were generated with the same version of uniffi as the Rust scaffolding code.
    let out_dir = env::var("OUT_DIR").map_err(|_| anyhow::anyhow!("$OUT_DIR missing?!"))?;
    run_uniffi_bindgen_scaffolding(&out_dir, idl_file)
}

#[cfg(not(feature = "builtin-bindgen"))]
fn run_uniffi_bindgen_scaffolding(out_dir: &str, idl_file: &str) -> Result<()> {
    let status = Command::new("uniffi-bindgen")
        .args(&["scaffolding", "--out-dir", out_dir, idl_file])
        .status()
        .context("failed to run `uniffi-bindgen`")?;
    if !status.success() {
        bail!("Error while generating scaffolding code");
    }
    Ok(())
}

#[cfg(feature = "builtin-bindgen")]
fn run_uniffi_bindgen_scaffolding(out_dir: &str, idl_file: &str) -> Result<()> {
    uniffi_bindgen::generate_component_scaffolding(idl_file, Some(out_dir), None, true)
}
