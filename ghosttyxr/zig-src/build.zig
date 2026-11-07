//! Orb XR native library build.
//!
//! Cross-compiles `libghosttyxr.so` for `aarch64-linux-android` API 24.
//! `build_targets` is arm64-only; the only Intel reference left is
//! `ndkPrebuiltTag()` returning `darwin-x86_64`, because NDK r28 still only
//! ships an `x86_64` darwin prebuilt toolchain (Apple Silicon runs it under
//! Rosetta 2). Flip the macOS-aarch64 branch to `aarch64` when Google
//! publishes a `darwin-arm64` prebuilt.
//!
//! ## Required environment
//!
//! `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`) must point at an Android NDK
//! install. The build fails fast if neither is set — there is no silent
//! default, because a wrong sysroot produces a broken `.so` that only fails
//! at `dlopen` time on the headset.
//!
//! ## Toolchain
//!
//! Zig **0.16.0** (pinned via `.zig-version`, resolved by anyzig) — the
//! version upstream ghostty `main` requires. ghostty and zigimg come from
//! official upstream; openssl-zig is pinned to the 0.16 stdlib-rename branch
//! until upstream catches up (pins + rationale in build.zig.zon).

const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("src/ndk.zig");

const build_targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
};

fn ndkPrebuiltTag() []const u8 {
    const os_part = switch (builtin.os.tag) {
        .macos => "darwin",
        .linux => "linux",
        .windows => "windows",
        else => @panic("Unsupported host OS for Android NDK prebuilt toolchain"),
    };

    // NDK r28 still only ships `darwin-x86_64`; Apple Silicon hosts run it
    // through Rosetta 2. Track https://developer.android.com/ndk/downloads
    // for a future darwin-arm64 prebuilt.
    const arch_part = switch (builtin.cpu.arch) {
        .x86_64 => "x86_64",
        .aarch64 => if (builtin.os.tag == .macos) "x86_64" else "aarch64",
        else => @panic("Unsupported host architecture for Android NDK prebuilt toolchain"),
    };

    return std.fmt.comptimePrint("{s}-{s}", .{ os_part, arch_part });
}

/// Some installers point `ANDROID_NDK_HOME` at the version-grouped parent
/// directory rather than at a specific NDK version. If `toolchains/llvm` is
/// missing, descend one level to the first child directory.
fn resolveNdkHome(b: *std.Build, ndk_root: []const u8) []const u8 {
    if (ndk_root.len == 0) return ndk_root;

    const io = b.graph.io;
    const cwd: std.Io.Dir = .cwd();
    const toolchains_path = b.pathJoin(&.{ ndk_root, "toolchains", "llvm" });
    cwd.access(io, toolchains_path, .{}) catch {
        var dir = cwd.openDir(io, ndk_root, .{ .iterate = true }) catch return ndk_root;
        defer dir.close(io);

        var iter = dir.iterate();
        while (iter.next(io) catch null) |entry| {
            if (entry.kind != .directory) continue;
            return b.pathJoin(&.{ ndk_root, entry.name });
        }
        return ndk_root;
    };

    return ndk_root;
}

fn requireNdkRoot(b: *std.Build) []const u8 {
    if (b.graph.environ_map.get("ANDROID_NDK_HOME")) |v| if (v.len > 0) return v;
    if (b.graph.environ_map.get("ANDROID_NDK_ROOT")) |v| if (v.len > 0) return v;
    std.debug.panic(
        "ghosttyxr build: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) is not set. " ++
            "Install the NDK (e.g. via Android Studio's SDK Manager) and export " ++
            "ANDROID_NDK_HOME=<path-to-ndk>.",
        .{},
    );
}

fn buildNativeLibrary(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const ndk_root = requireNdkRoot(b);
    const ndk_home = resolveNdkHome(b, ndk_root);

    const android_target = ndk.getAndroidTriple(target.result) catch {
        std.debug.panic("target must be Android", .{});
    };
    std.debug.assert(target.result.os.tag == .linux);
    const android_api_version: u32 = target.result.os.version_range.linux.android;

    const ndk_sysroot = b.pathJoin(&.{
        ndk_home,
        "toolchains",
        "llvm",
        "prebuilt",
        ndkPrebuiltTag(),
        "sysroot",
    });

    const libc_config = ndk.createLibC(
        b,
        android_target,
        android_api_version,
        ndk_sysroot,
    );

    const include_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "include" });
    const target_include_dir = b.pathJoin(&.{ include_dir, android_target });

    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    });
    const zigimg_dep = b.dependency("zigimg", .{
        .target = target,
        .optimize = optimize,
    });

    // libssh2 + openssl wire-in for task #4 (SSH bridge). libssh2's build
    // script links openssl as a static dep; we then pull libssh2's headers
    // through `libssh2_upstream` for the bridge `@cInclude("libssh2.h")`.
    // Gated behind `-Dssh=true` so the task #1 smoke build doesn't have to
    // wait on the openssl+libssh2 compile.
    const enable_ssh = b.option(bool, "ssh", "Wire libssh2 + openssl into the bridge") orelse false;

    // Comptime-visible build options. `zignal_png.zig` gates the
    // `ghosttyxr_ssh.zig` import on `enable_ssh` so the snapshot-only build
    // doesn't pull the libssh2 dep tree.
    const build_options = b.addOptions();
    build_options.addOption(bool, "enable_ssh", enable_ssh);

    // Root module = `zignal_png.zig` — chuchu pattern. That file lives in
    // `src/bridge/` and force-imports the bridge submodules (snapshot, ssh)
    // in a comptime block so every `export fn` gets compiled into the .so.
    const root_module = b.createModule(.{
        .root_source_file = b.path("src/bridge/zignal_png.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .unwind_tables = .none,
        .omit_frame_pointer = true,
    });
    root_module.addIncludePath(b.path("src/bridge"));
    root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    root_module.addImport("zigimg", zigimg_dep.module("zigimg"));
    root_module.addImport("build_options", build_options.createModule());

    const ssh_artifact: ?*std.Build.Step.Compile = if (enable_ssh) blk: {
        const libssh2_dep = b.dependency("libssh2", .{
            .target = target,
            .optimize = optimize,
            .libc_file = libc_config,
        });
        const libssh2_upstream = libssh2_dep.builder.dependency("libssh2_upstream", .{
            .target = target,
            .optimize = optimize,
        });
        root_module.addIncludePath(libssh2_upstream.path("include"));
        break :blk libssh2_dep.artifact("ssh2");
    } else null;

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "ghosttyxr",
        .root_module = root_module,
    });
    lib.link_function_sections = true;
    lib.link_data_sections = true;
    lib.link_gc_sections = true;
    lib.link_eh_frame_hdr = false;
    lib.lto = .thin;

    // Support 16kb page sizes, required for Android 15+ (mirrors upstream
    // ghostty's GhosttyLibVt Android handling).
    lib.link_z_max_page_size = 16384;

    lib.root_module.addIncludePath(.{ .cwd_relative = include_dir });
    lib.root_module.addIncludePath(.{ .cwd_relative = target_include_dir });

    const api_dir = b.fmt("{d}", .{android_api_version});
    const lib_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "lib", android_target, api_dir });
    lib.root_module.addLibraryPath(.{ .cwd_relative = lib_dir });

    lib.setLibCFile(libc_config);
    if (ssh_artifact) |ssh| lib.root_module.linkLibrary(ssh);
    lib.root_module.linkSystemLibrary("log", .{});
    lib.root_module.linkSystemLibrary("z", .{});
    // Restrict exported symbols to JNI + ghosttyxr_* surface — keeps the .so
    // small and stops accidental ABI leakage from transitively-linked deps.
    lib.version_script = b.path("src/bridge/version-script.map");

    b.installArtifact(lib);
    return lib;
}

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});

    const native_step = b.step("native", "Build native JNI library");
    const jni_step = b.step("jni", "Build native library and copy to android jniLibs");

    for (build_targets) |target_query| {
        const resolved_target = b.resolveTargetQuery(target_query);
        const native_lib = buildNativeLibrary(b, resolved_target, optimize);
        native_step.dependOn(&native_lib.step);

        const abi_name = ndk.getOutputDir(resolved_target.result) catch unreachable;

        // `UpdateSourceFiles` creates the destination directory and copies
        // without shelling out, so `zig build jni` works on Windows hosts too.
        const copy_to_jni_libs = b.addUpdateSourceFiles();
        copy_to_jni_libs.addCopyFileToSource(
            native_lib.getEmittedBin(),
            b.fmt("../android/app/src/main/jniLibs/{s}/libghosttyxr.so", .{abi_name}),
        );

        jni_step.dependOn(&copy_to_jni_libs.step);
    }

    const fmt_check = b.addFmt(.{ .paths = &.{ "src", "build.zig", "build.zig.zon" } });
    const fmt_step = b.step("fmt", "Format Zig files");
    fmt_step.dependOn(&fmt_check.step);
}
