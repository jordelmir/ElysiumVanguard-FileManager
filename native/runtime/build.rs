fn main() {
    println!("cargo:rerun-if-changed=src/spawn_shim.c");
    println!("cargo:rerun-if-changed=src/spawn_shim.h");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        cc::Build::new()
            .file("src/spawn_shim.c")
            .include("src")
            .flag("-std=c17")
            .flag("-Wall")
            .flag("-Wextra")
            .flag("-Werror")
            .compile("elysium_spawn_shim");
    }
}
